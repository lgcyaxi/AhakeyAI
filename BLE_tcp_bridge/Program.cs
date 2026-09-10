using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Security.Cryptography;
using Windows.Storage.Streams;

namespace BLE_tcp_driver
{
    // Connection state is owned by the host's message-loop thread. Watcher and
    // WinRT callbacks are posted back there before touching connection objects.
    class BleCore : IDisposable
    {
        private readonly SynchronizationContext context = SynchronizationContext.Current;
        private readonly List<DeviceInformation> devices = new List<DeviceInformation>();
        private readonly List<GattDeviceService> services = new List<GattDeviceService>();
        private readonly SemaphoreSlim writes = new SemaphoreSlim(1, 1);
        private DeviceWatcher watcher;
        private int generation;
        private CancellationTokenSource connectionCancellation;
        private GattSession session;
        private bool notificationsReady;
        public bool IsConnecting { get; private set; }
        public bool IsReady => notificationsReady && CurrentWriteCharacteristic != null
            && CurrentDataCharacteristic != null && CurrentDevice != null
            && CurrentDevice.ConnectionStatus == BluetoothConnectionStatus.Connected;
        public BluetoothLEDevice CurrentDevice { get; private set; }
        public GattCharacteristic CurrentWriteCharacteristic { get; set; }
        public GattCharacteristic CurrentDataCharacteristic { get; set; }
        public GattCharacteristic CurrentNotifyCharacteristic { get; set; }
        public List<GattCharacteristic> CharacteristicList { get; } = new List<GattCharacteristic>();
        public event Action<DeviceInformation> DeviceAdded;
        public event Action<BluetoothLEDevice> ConnectDeviceSuccess;
        public event Action<BluetoothLEDevice> DeviceDisconnected;
        public event Action<GattCharacteristic> CharacteristicAdded;
        public event Action<GattCharacteristic, byte[]> WriteDataSuccess;
        public event Action<GattCharacteristic, byte[]> ReceiveNotifyData;
        public event Action AllCharacteristicsDiscovered;
        public event Action<string> ConnectionFailed;

        private void Post(Action action)
        {
            if (context != null) context.Post(_ => action(), null);
            else action();
        }

        public Task DispatchAsync(Action action)
        {
            var completion = new TaskCompletionSource<bool>();
            Post(() =>
            {
                try { action(); completion.TrySetResult(true); }
                catch (Exception ex) { completion.TrySetException(ex); }
            });
            return completion.Task;
        }

        public void StartBleDeviceWatcher()
        {
            StopBleDeviceWatcher();
            devices.Clear();
            var next = DeviceInformation.CreateWatcher(
                "(System.Devices.Aep.ProtocolId:=\"{bb7bb05e-5972-42b5-94fc-76eaa7084d49}\")",
                new[] { "System.Devices.Aep.DeviceAddress", "System.Devices.Aep.Bluetooth.Le.IsConnectable" },
                DeviceInformationKind.AssociationEndpoint);
            watcher = next;
            next.Added += (sender, info) => Post(() =>
            {
                if (sender != watcher) return;
                if (devices.All(d => d.Id != info.Id)) devices.Add(info);
                DeviceAdded?.Invoke(info);
            });
            next.Updated += (sender, update) => Post(() =>
            {
                if (sender != watcher) return;
                var info = devices.FirstOrDefault(d => d.Id == update.Id);
                if (info == null) return;
                info.Update(update);
                DeviceAdded?.Invoke(info);
            });
            next.Removed += (sender, update) => Post(() =>
            {
                if (sender == watcher) devices.RemoveAll(d => d.Id == update.Id);
            });
            next.Start();
        }

        public IReadOnlyList<DeviceInformation> GetDevices() => devices.ToArray();

        public void StopBleDeviceWatcher()
        {
            var previous = watcher;
            watcher = null; // Ignore callbacks already queued by an old scan.
            if (previous != null && (previous.Status == DeviceWatcherStatus.Started
                || previous.Status == DeviceWatcherStatus.EnumerationCompleted)) previous.Stop();
        }

        public async void ConnectDeviceByInfo(DeviceInformation info)
        {
            if (IsConnecting || info == null) return;
            Dispose();
            IsConnecting = true;
            int attempt = generation;
            connectionCancellation = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            CancellationToken token = connectionCancellation.Token;
            try
            {
                var device = await BluetoothLEDevice.FromIdAsync(info.Id).AsTask(token);
                if (attempt != generation) { device?.Dispose(); return; }
                if (device == null) throw new InvalidOperationException("open-device: unavailable or access denied");
                CurrentDevice = device;
                device.ConnectionStatusChanged += OnConnectionStatusChanged;
                var openedSession = await GattSession.FromDeviceIdAsync(device.BluetoothDeviceId).AsTask(token);
                if (attempt != generation) { openedSession?.Dispose(); return; }
                session = openedSession;
                // FromIdAsync only opens an object; it does not establish a BLE
                // link. Keep the GATT session alive while discovering services.
                if (session != null && session.CanMaintainConnection) session.MaintainConnection = true;
                // MaintainConnection starts asynchronously. Retain the same
                // session during transient Unreachable results instead of
                // cancelling the connection request immediately on every retry.
                // Reuse Windows' known service graph on reconnect. Readiness
                // still requires live notification subscription and a live link.
                var result = await device.GetGattServicesAsync(BluetoothCacheMode.Cached).AsTask(token);
                for (int retry = 0; result.Status == GattCommunicationStatus.Unreachable && retry < 3; retry++)
                {
                    foreach (var unused in result.Services) unused.Dispose();
                    await Task.Delay(1000 * (retry + 1), token);
                    if (attempt != generation) return;
                    result = await device.GetGattServicesAsync(BluetoothCacheMode.Uncached).AsTask(token);
                }
                if (attempt != generation)
                {
                    foreach (var stale in result.Services) stale.Dispose();
                    return;
                }
                if (result.Status != GattCommunicationStatus.Success)
                    throw new InvalidOperationException("discover-services: " + result.Status);
                services.AddRange(result.Services);
                GattCommunicationStatus? characteristicFailure = null;
                foreach (var service in services.ToArray())
                {
                    var chars = await service.GetCharacteristicsAsync(BluetoothCacheMode.Cached).AsTask(token);
                    if (chars.Status != GattCommunicationStatus.Success || chars.Characteristics.Count == 0)
                        chars = await service.GetCharacteristicsAsync(BluetoothCacheMode.Uncached).AsTask(token);
                    if (attempt != generation) return;
                    if (chars.Status != GattCommunicationStatus.Success)
                    {
                        characteristicFailure = chars.Status;
                        continue;
                    }
                    foreach (var c in chars.Characteristics)
                    {
                        CharacteristicList.Add(c);
                        switch (Utilities.ConvertUuidToShortId(c.Uuid))
                        {
                            case 0x7341: CurrentDataCharacteristic = c; break;
                            case 0x7343: CurrentWriteCharacteristic = c; break;
                            case 0x7344: CurrentNotifyCharacteristic = c; break;
                        }
                    }
                }
                if (CurrentDataCharacteristic == null || CurrentWriteCharacteristic == null || CurrentNotifyCharacteristic == null)
                    throw new InvalidOperationException(characteristicFailure.HasValue
                        ? "discover-characteristics: " + characteristicFailure.Value
                        : "discover-characteristics: required AhaKey characteristics missing");
                var notify = CurrentNotifyCharacteristic;
                var notificationStatus = await notify.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify).AsTask(token);
                for (int retry = 0; notificationStatus == GattCommunicationStatus.Unreachable && retry < 3; retry++)
                {
                    await Task.Delay(1000 * (retry + 1), token);
                    if (attempt != generation) return;
                    notificationStatus = await notify.WriteClientCharacteristicConfigurationDescriptorAsync(
                        GattClientCharacteristicConfigurationDescriptorValue.Notify).AsTask(token);
                }
                if (attempt != generation) return;
                if (notificationStatus != GattCommunicationStatus.Success)
                    throw new InvalidOperationException("enable-notifications: " + notificationStatus);
                notify.ValueChanged += OnValueChanged;
                notificationsReady = true;
                IsConnecting = false;
                ConnectDeviceSuccess?.Invoke(device);
                foreach (var c in CharacteristicList) CharacteristicAdded?.Invoke(c);
                AllCharacteristicsDiscovered?.Invoke();
            }
            catch (Exception error)
            {
                if (attempt != generation) return;
                // Only stage names/status codes are exposed; never Windows IDs,
                // device addresses, or exception messages from native APIs.
                string reason = error is InvalidOperationException && error.Message.StartsWith("discover-")
                    || error is InvalidOperationException && error.Message.StartsWith("enable-notifications:")
                    || error is InvalidOperationException && error.Message.StartsWith("open-device:")
                    ? error.Message : error is OperationCanceledException ? "connect: timed out" : "connect: failed (" + error.GetType().Name + ")";
                Dispose();
                ConnectionFailed?.Invoke(reason);
            }
            finally
            {
                if (attempt == generation)
                {
                    IsConnecting = false;
                    connectionCancellation?.Dispose();
                    connectionCancellation = null;
                }
            }
        }

        private void OnConnectionStatusChanged(BluetoothLEDevice sender, object args) => Post(() =>
        {
            if (sender != CurrentDevice || IsConnecting) return;
            if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
            {
                DeviceDisconnected?.Invoke(sender);
                Dispose();
            }
        });

        private void OnValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
        {
            byte[] bytes;
            CryptographicBuffer.CopyToByteArray(args.CharacteristicValue, out bytes);
            Post(() => { if (sender == CurrentNotifyCharacteristic && notificationsReady) ReceiveNotifyData?.Invoke(sender, bytes); });
        }

        // Kept for the optional legacy diagnostics form. Subscription now
        // belongs to connection establishment and is enabled exactly once.
        public void EnableNotifications(GattCharacteristic characteristic) { }

        public void WriteDataToCharacterstuc(GattCharacteristic c, byte[] data)
        {
            if (c == null || data == null || data.Length == 0) return;
            Post(async () =>
            {
                int attempt = generation;
                await writes.WaitAsync();
                try
                {
                    if (attempt != generation || !IsReady) return;
                    var result = await c.WriteValueAsync(CryptographicBuffer.CreateFromByteArray(data), GattWriteOption.WriteWithResponse);
                    if (attempt == generation && result == GattCommunicationStatus.Success) WriteDataSuccess?.Invoke(c, data);
                }
                catch { /* A disconnected write must not terminate the host. */ }
                finally { writes.Release(); }
            });
        }

        public void Dispose()
        {
            generation++;
            IsConnecting = false;
            connectionCancellation?.Cancel();
            connectionCancellation?.Dispose();
            connectionCancellation = null;
            notificationsReady = false;
            if (CurrentNotifyCharacteristic != null) CurrentNotifyCharacteristic.ValueChanged -= OnValueChanged;
            if (CurrentDevice != null) CurrentDevice.ConnectionStatusChanged -= OnConnectionStatusChanged;
            CurrentWriteCharacteristic = null;
            CurrentDataCharacteristic = null;
            CurrentNotifyCharacteristic = null;
            CharacteristicList.Clear();
            if (session != null)
            {
                try { session.MaintainConnection = false; } catch { }
                try { session.Dispose(); } catch { }
                session = null;
            }
            foreach (var service in services) { try { service.Dispose(); } catch { } }
            services.Clear();
            try { CurrentDevice?.Dispose(); } catch { }
            CurrentDevice = null;
        }
    }

    static class Utilities
    {
        public static ushort ConvertUuidToShortId(Guid uuid)
        {
            var bytes = uuid.ToByteArray();
            return (ushort)(bytes[0] | (bytes[1] << 8));
        }
        public static byte[] ReadBufferToBytes(IBuffer buffer)
        {
            var data = new byte[buffer.Length];
            using (var reader = DataReader.FromBuffer(buffer)) reader.ReadBytes(data);
            return data;
        }
    }

    internal static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            // Install a message-loop context without creating a Form or tray.
            SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
            if (args.Any(a => a.Equals("--headless", StringComparison.OrdinalIgnoreCase)))
            {
                try { using (var host = new BridgeHost(args)) Application.Run(host); }
                catch { return 1; }
            }
            else Application.Run(new Form1());
            return 0;
        }
    }
}

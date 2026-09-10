using System;
using System.Diagnostics;
using System.Linq;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace BLE_tcp_driver
{
    // The production entry point used by Studio: a message loop with no Form,
    // NotifyIcon, startup registry entry, or independent user-facing lifetime.
    sealed class BridgeHost : ApplicationContext
    {
        private readonly BleCore core = new BleCore();
        private readonly AppConfig config;
        private readonly TcpServer server;
        private readonly Timer timer = new Timer { Interval = 1000 };
        private readonly Process parent;
        private readonly bool bluetoothEnabled;
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();
        private bool reconnect = true;
        private bool disposed;
        private DateTime retryAfter = DateTime.MinValue;
        private string state = "scanning";
        private string error = "";
        private string selectedId;
        private readonly string lifecycleToken;
        private bool shutdownRequested;

        public BridgeHost(string[] args)
        {
            bluetoothEnabled = !args.Contains("--no-bluetooth");
            // Offline protocol diagnostics neither read nor migrate real user
            // settings and never scan/connect the user's Bluetooth adapter.
            config = bluetoothEnabled ? AppConfig.Load() : new AppConfig();
            int port = ReadOption(args, "--port", config.ServerPort);
            if (port < 1 || port > 65535) throw new ArgumentException("Invalid port");
            int parentId = ReadOption(args, "--parent-pid", 0);
            if (parentId != 0) parent = Process.GetProcessById(parentId);
            int tokenIndex = Array.IndexOf(args, "--lifecycle-token");
            lifecycleToken = tokenIndex >= 0 && tokenIndex + 1 < args.Length
                ? args[tokenIndex + 1] : null;
            core.DeviceAdded += _ => TryReconnect();
            core.ConnectDeviceSuccess += Connected;
            core.DeviceDisconnected += _ => Failed("connection: disconnected");
            core.ConnectionFailed += Failed;
            server = new TcpServer(core, port) { HandleDeviceControl = HandleControl };
            server.Start();
            if (bluetoothEnabled) StartScan();
            else state = "disconnected";
            timer.Tick += (_, __) =>
            {
                if (shutdownRequested || (parent != null && parent.HasExited)) { ExitThread(); return; }
                TryReconnect();
            };
            timer.Start();
        }

        private static int ReadOption(string[] args, string key, int fallback)
        {
            int index = Array.IndexOf(args, key);
            if (index < 0) return fallback;
            int value;
            if (index + 1 >= args.Length || !int.TryParse(args[index + 1], out value)) throw new ArgumentException(key);
            return value;
        }

        private void StartScan()
        {
            if (!bluetoothEnabled) return;
            try
            {
                core.StartBleDeviceWatcher();
                if (!core.IsConnecting && !core.IsReady) state = "scanning";
                error = "";
            }
            catch { Failed("scan: Bluetooth unavailable or access denied"); }
        }

        private void TryReconnect()
        {
            if (disposed || !bluetoothEnabled || !reconnect || (!config.HasSavedDevice && selectedId == null)
                || core.IsConnecting || core.IsReady || DateTime.UtcNow < retryAfter) return;
            var candidate = core.GetDevices().FirstOrDefault(d => selectedId != null
                ? d.Id == selectedId : string.Equals(DeviceMac(d), config.BleMac, StringComparison.OrdinalIgnoreCase));
            if (candidate != null) Connect(candidate);
        }

        private void Connect(DeviceInformation candidate)
        {
            retryAfter = DateTime.UtcNow.AddSeconds(8);
            state = "connecting";
            error = "";
            server.ResetDeviceStatus();
            core.ConnectDeviceByInfo(candidate);
        }

        private void Connected(BluetoothLEDevice device)
        {
            state = core.IsReady ? "ready" : "connecting";
            config.BleName = device.Name;
            var mac = BitConverter.GetBytes(device.BluetoothAddress);
            Array.Reverse(mac);
            config.BleMac = BitConverter.ToString(mac, 2, 6).Replace('-', ':');
            config.Save();
            core.WriteDataToCharacterstuc(core.CurrentWriteCharacteristic, ProtocolHelper.DeviceStatusQueryCommand);
            if (ProtocolHelper.LastClaudeState != null)
                core.WriteDataToCharacterstuc(core.CurrentWriteCharacteristic, ProtocolHelper.LastClaudeState);
        }

        private void Failed(string reason)
        {
            state = "error";
            error = reason;
            retryAfter = DateTime.UtcNow.AddSeconds(8);
            server.ResetDeviceStatus();
        }

        private byte[] HandleControl(PacketType type, byte[] payload)
        {
            if (type == PacketType.QueryBridgeInfo)
                return ProtocolHelper.BuildPacket(PacketType.BridgeInfoResp, Encoding.UTF8.GetBytes(
                    json.Serialize(new { protocol = 2, pid = Process.GetCurrentProcess().Id,
                        parentPid = parent == null ? 0 : parent.Id })));
            if (type == PacketType.ShutdownBridge)
            {
                if (lifecycleToken == null || payload == null || payload.Length > 128
                    || Encoding.UTF8.GetString(payload) != lifecycleToken) return Reply(false, "owner-mismatch");
                reconnect = false;
                shutdownRequested = true;
                core.Dispose();
                server.ResetDeviceStatus();
                state = "stopping";
                return Reply(true, "stopping");
            }
            if (type == PacketType.ListDevices)
            {
                // Bound the UTF-8 response to the existing uint16 frame length;
                // long Windows identifiers must never wrap the length field.
                var items = core.GetDevices().Where(d => !string.IsNullOrWhiteSpace(d.Name))
                    .Select(d => new { id = d.Id, name = d.Name, mac = DeviceMac(d) }).Take(100).ToList();
                byte[] bytes;
                do
                {
                    bytes = Encoding.UTF8.GetBytes(json.Serialize(new { devices = items,
                        state = core.IsReady ? "ready" : state, error, saved = config.HasSavedDevice }));
                    if (bytes.Length <= 60000) break;
                    items.RemoveAt(items.Count - 1);
                } while (items.Count > 0);
                return ProtocolHelper.BuildPacket(PacketType.DeviceListResp, bytes);
            }
            if (type == PacketType.DisconnectDevice)
            {
                reconnect = false;
                selectedId = null;
                core.Dispose();
                state = "disconnected";
                error = "";
                server.ResetDeviceStatus();
                return Reply(true, "disconnected");
            }
            if (!bluetoothEnabled) return Reply(false, "Bluetooth disabled for diagnostics");
            if (type == PacketType.ScanDevices)
            {
                StartScan();
                return Reply(state != "error", state == "error" ? error : "scanning");
            }
            if (type == PacketType.SelectDevice)
            {
                if (payload == null || payload.Length == 0 || payload.Length > 4096) return Reply(false, "Invalid device selection");
                string id;
                try { id = new UTF8Encoding(false, true).GetString(payload); }
                catch { return Reply(false, "Invalid UTF-8 device selection"); }
                var selected = core.GetDevices().FirstOrDefault(d => d.Id == id);
                if (selected == null) return Reply(false, "Device no longer available; scan again");
                if (core.IsConnecting) return Reply(false, "Connection in progress");
                reconnect = true;
                selectedId = selected.Id;
                Connect(selected);
                return Reply(true, "connecting");
            }
            return Reply(false, "Unsupported device control");
        }

        private byte[] Reply(bool ok, string message) => ProtocolHelper.BuildPacket(
            PacketType.DeviceControlResp, Encoding.UTF8.GetBytes(json.Serialize(new { ok, message })));

        private static string DeviceMac(DeviceInformation device)
        {
            object value;
            if (!device.Properties.TryGetValue("System.Devices.Aep.DeviceAddress", out value) || value == null) return "";
            string compact = value.ToString().Replace(":", "").Replace("-", "");
            return compact.Length == 12 ? string.Join(":", Enumerable.Range(0, 6).Select(i => compact.Substring(i * 2, 2))).ToUpperInvariant() : "";
        }

        protected override void ExitThreadCore()
        {
            Cleanup();
            base.ExitThreadCore();
        }
        protected override void Dispose(bool disposing)
        {
            if (disposing) Cleanup();
            base.Dispose(disposing);
        }
        private void Cleanup()
        {
            if (disposed) return;
            disposed = true;
            timer.Stop();
            timer.Dispose();
            server.Stop();
            core.StopBleDeviceWatcher();
            core.Dispose();
            parent?.Dispose();
        }
    }
}

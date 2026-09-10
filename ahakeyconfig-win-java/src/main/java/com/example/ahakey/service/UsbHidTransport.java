package com.example.ahakey.service;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class UsbHidTransport implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(UsbHidTransport.class);

    private static final int DIGCF_PRESENT = 0x00000002;
    private static final int DIGCF_DEVICEINTERFACE = 0x00000010;
    private static final int ERROR_NO_MORE_ITEMS = 259;
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int OPEN_EXISTING = 3;
    private static final int LEGACY_REPORT_LENGTH = 65;
    private static final byte USB_COMMAND_PACKET = (byte) 0xA1;
    private static final byte USB_DATA_PACKET = (byte) 0xA2;

    private Pointer readHandle;
    private Pointer writeHandle;
    private String devicePath;
    private DeviceProfile deviceProfile;
    private Thread readerThread;
    private volatile boolean running;
    private volatile Consumer<byte[]> frameConsumer;

    static record DeviceProfile(
        String path,
        byte reportId,
        int reportLength,
        boolean allowLegacyNoReportIdFallback
    ) {
    }

    public static boolean isPresent() {
        return findDeviceProfile() != null;
    }

    public synchronized void open(Consumer<byte[]> onFrame) throws IOException {
        if (isOpen()) {
            return;
        }
        DeviceProfile profile = findDeviceProfile();
        if (profile == null) {
            throw new IOException("USB HID device not found");
        }
        String path = profile.path();
        Pointer r = Kernel32.INSTANCE.CreateFile(
            new WString(path),
            GENERIC_READ,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            Pointer.NULL,
            OPEN_EXISTING,
            0,
            Pointer.NULL
        );
        if (isInvalidHandle(r)) {
            throw new IOException("Open USB HID read failed: " + Native.getLastError());
        }
        Pointer w = Kernel32.INSTANCE.CreateFile(
            new WString(path),
            GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            Pointer.NULL,
            OPEN_EXISTING,
            0,
            Pointer.NULL
        );
        if (isInvalidHandle(w)) {
            Kernel32.INSTANCE.CloseHandle(r);
            throw new IOException("Open USB HID write failed: " + Native.getLastError());
        }

        readHandle = r;
        writeHandle = w;
        devicePath = path;
        deviceProfile = profile;
        frameConsumer = onFrame;
        running = true;
        startReader();
        logger.info("USB HID connected: {}", path);
    }

    /**
     * Opens the selected HID interface and proves that it is an AhaKey
     * configuration channel before returning it to the caller.
     */
    public byte[] openValidated(
        byte[] statusQuery,
        Predicate<byte[]> statusValidator,
        Consumer<byte[]> onFrame,
        long timeoutMs
    ) throws IOException {
        if (statusQuery == null || statusValidator == null || timeoutMs <= 0) {
            throw new IllegalArgumentException("USB validation requires a query, validator, and positive timeout");
        }
        synchronized (this) {
            if (isOpen()) {
                throw new IOException("USB HID is already open");
            }
        }

        CountDownLatch statusReceived = new CountDownLatch(1);
        AtomicReference<byte[]> validatedFrame = new AtomicReference<>();
        open(frame -> {
            try {
                if (statusValidator.test(frame) && validatedFrame.compareAndSet(null, frame)) {
                    statusReceived.countDown();
                }
            } catch (RuntimeException e) {
                logger.debug("USB HID validation rejected a frame: {}", e.getMessage());
            }
        });

        try {
            sendCommand(statusQuery);
            if (!statusReceived.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("USB HID did not return a valid AhaKey status frame within " + timeoutMs + " ms");
            }
            byte[] frame = validatedFrame.get();
            if (frame == null) {
                throw new IOException("USB HID validation completed without a status frame");
            }
            frameConsumer = onFrame;
            return frame;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("USB HID validation was interrupted", e);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public synchronized boolean isOpen() {
        return devicePath != null && !devicePath.isBlank()
            && readHandle != null && !isInvalidHandle(readHandle)
            && writeHandle != null && !isInvalidHandle(writeHandle);
    }

    public synchronized void sendCommand(byte[] frame) throws IOException {
        ensureOpen();
        writeReport(buildOutputReport(deviceProfile, USB_COMMAND_PACKET, frame));
    }

    public synchronized void sendData(byte[] data) throws IOException {
        ensureOpen();
        int chunkCapacity = deviceProfile.reportLength() - 3;
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(chunkCapacity, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
            writeReport(buildOutputReport(deviceProfile, USB_DATA_PACKET, chunk));
            offset += len;
            sleepQuietly(2);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static byte[] buildCommandReport(DeviceProfile profile, byte[] frame) throws IOException {
        return buildOutputReport(profile, USB_COMMAND_PACKET, frame);
    }

    private static byte[] buildOutputReport(DeviceProfile profile, byte packetType, byte[] data) throws IOException {
        if (profile == null) {
            throw new IOException("USB HID profile is unavailable");
        }
        int capacity = profile.reportLength() - 3;
        if (data.length > capacity) {
            throw new IOException("USB HID frame too large: " + data.length + " > " + capacity);
        }
        byte[] report = new byte[profile.reportLength()];
        report[0] = profile.reportId();
        report[1] = packetType;
        report[2] = (byte) data.length;
        System.arraycopy(data, 0, report, 3, data.length);
        return report;
    }

    private void writeReport(byte[] report) throws IOException {
        IntByReference written = new IntByReference();
        Pointer h = writeHandle;
        if (h == null || isInvalidHandle(h)) {
            throw new IOException("USB HID write handle not connected");
        }
        boolean ok = Kernel32.INSTANCE.WriteFile(h, report, report.length, written, Pointer.NULL)
            && written.getValue() == report.length;
        if (!ok && deviceProfile.allowLegacyNoReportIdFallback() && report.length > 1) {
            byte[] withoutReportId = Arrays.copyOfRange(report, 1, report.length);
            written.setValue(0);
            ok = Kernel32.INSTANCE.WriteFile(h, withoutReportId, withoutReportId.length, written, Pointer.NULL)
                && written.getValue() == withoutReportId.length;
        }
        if (!ok) {
            throw new IOException("USB HID write failed: " + Native.getLastError());
        }
    }

    private void startReader() {
        DeviceProfile activeProfile = deviceProfile;
        readerThread = new Thread(() -> {
            byte[] report = new byte[activeProfile.reportLength()];
            while (running) {
                Pointer h = readHandle;
                if (h == null || isInvalidHandle(h)) {
                    break;
                }
                try {
                    Arrays.fill(report, (byte) 0);
                    IntByReference read = new IntByReference();
                    boolean ok = Kernel32.INSTANCE.ReadFile(h, report, report.length, read, Pointer.NULL);
                    if (!ok || read.getValue() <= 0) {
                        int err = Native.getLastError();
                        if (running) {
                            logger.warn("USB HID read stopped: {}", err);
                        }
                        break;
                    }
                    byte[] frame = extractFrame(report, read.getValue());
                    // 在调用回调前再次检查 running 状态
                    if (running && frame != null && frameConsumer != null) {
                        frameConsumer.accept(frame);
                    }
                } catch (Exception e) {
                    if (running) {
                        logger.warn("USB HID read error: {}", e.getMessage());
                    }
                    break;
                }
            }
            // 线程退出时自行关闭 handles（避免主线程在 ReadFile 阻塞时关闭）
            logger.debug("USB reader: 线程退出，自行关闭 handles");
            synchronized (UsbHidTransport.this) {
                if (readHandle != null && !isInvalidHandle(readHandle)) {
                    try {
                        Kernel32.INSTANCE.CloseHandle(readHandle);
                        logger.debug("USB reader: 自行关闭 readHandle");
                    } catch (Exception e) {
                        logger.warn("USB reader: 关闭 readHandle 失败 - {}", e.getMessage());
                    }
                    readHandle = null;
                }
                if (writeHandle != null && !isInvalidHandle(writeHandle)) {
                    try {
                        Kernel32.INSTANCE.CloseHandle(writeHandle);
                        logger.debug("USB reader: 自行关闭 writeHandle");
                    } catch (Exception e) {
                        logger.warn("USB reader: 关闭 writeHandle 失败 - {}", e.getMessage());
                    }
                    writeHandle = null;
                }
                devicePath = null;
                deviceProfile = null;
                frameConsumer = null;
            }
        }, "usb-hid-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static byte[] extractFrame(byte[] data, int len) {
        int start = -1;
        for (int i = 0; i + 1 < len; i++) {
            if (data[i] == (byte) 0xAA && data[i + 1] == (byte) 0xBB) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        for (int i = start + 3; i + 1 < len; i++) {
            if (data[i] == (byte) 0xCC && data[i + 1] == (byte) 0xDD) {
                return Arrays.copyOfRange(data, start, i + 2);
            }
        }
        return null;
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("USB HID not connected");
        }
    }

    @Override
    public void close() {
        logger.debug("USB close: 开始关闭 USB 传输");
        Thread reader;
        synchronized (this) {
            running = false;
            reader = readerThread;
            readerThread = null;
            closeHandlesLocked();
            devicePath = null;
            deviceProfile = null;
            frameConsumer = null;
        }

        if (reader != null && reader != Thread.currentThread() && reader.isAlive()) {
            logger.debug("USB close: 等待读取线程退出");
            try {
                reader.join(2000);
                logger.debug("USB close: 读取线程已退出");
            } catch (InterruptedException e) {
                logger.warn("USB close: 等待线程退出被中断");
                Thread.currentThread().interrupt();
            }
        }
        logger.debug("USB close: 关闭完成");
    }

    private void closeHandlesLocked() {
        if (readHandle != null && !isInvalidHandle(readHandle)) {
            Kernel32.INSTANCE.CloseHandle(readHandle);
        }
        if (writeHandle != null && !isInvalidHandle(writeHandle)) {
            Kernel32.INSTANCE.CloseHandle(writeHandle);
        }
        readHandle = null;
        writeHandle = null;
    }

    private static boolean isInvalidHandle(Pointer h) {
        return h == null || Pointer.nativeValue(h) == 0 || Pointer.nativeValue(h) == -1;
    }

    private static DeviceProfile findDeviceProfile() {
        return findDeviceProfile(listDevicePaths());
    }

    static DeviceProfile findDeviceProfile(List<String> paths) {
        for (String path : paths) {
            String p = path.toLowerCase(Locale.ROOT);
            if (p.contains("vid_413c") && p.contains("pid_2107") && (p.contains("mi_01") || p.contains("col02"))) {
                return new DeviceProfile(path, (byte) 0, LEGACY_REPORT_LENGTH, true);
            }
        }
        for (String path : paths) {
            String p = path.toLowerCase(Locale.ROOT);
            if (p.contains("vid_413c") && p.contains("pid_2107")) {
                return new DeviceProfile(path, (byte) 0, LEGACY_REPORT_LENGTH, true);
            }
        }
        return null;
    }

    private static List<String> listDevicePaths() {
        Guid.GUID hidGuid = new Guid.GUID();
        Hid.INSTANCE.HidD_GetHidGuid(hidGuid);
        Pointer infoSet = SetupApi.INSTANCE.SetupDiGetClassDevs(hidGuid, Pointer.NULL, Pointer.NULL, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
        if (isInvalidHandle(infoSet)) {
            return List.of();
        }

        List<String> paths = new ArrayList<>();
        try {
            for (int index = 0; ; index++) {
                SP_DEVICE_INTERFACE_DATA data = new SP_DEVICE_INTERFACE_DATA();
                data.cbSize = data.size();
                boolean ok = SetupApi.INSTANCE.SetupDiEnumDeviceInterfaces(infoSet, Pointer.NULL, hidGuid, index, data);
                if (!ok) {
                    int err = Native.getLastError();
                    if (err == ERROR_NO_MORE_ITEMS) {
                        break;
                    }
                    throw new LastErrorException(err);
                }

                SP_DEVICE_INTERFACE_DETAIL_DATA detail = new SP_DEVICE_INTERFACE_DETAIL_DATA();
                detail.cbSize = Native.POINTER_SIZE == 8 ? 8 : 6;
                IntByReference required = new IntByReference();
                ok = SetupApi.INSTANCE.SetupDiGetDeviceInterfaceDetail(
                    infoSet,
                    data,
                    detail,
                    detail.size(),
                    required,
                    Pointer.NULL
                );
                if (ok && detail.DevicePath != null) {
                    String path = Native.toString(detail.DevicePath);
                    if (path != null && !path.isBlank()) {
                        paths.add(path);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("USB HID enumerate failed: {}", e.getMessage());
        } finally {
            SetupApi.INSTANCE.SetupDiDestroyDeviceInfoList(infoSet);
        }
        return paths;
    }

    public static class Guid {
        @Structure.FieldOrder({"Data1", "Data2", "Data3", "Data4"})
        public static class GUID extends Structure {
            public int Data1;
            public short Data2;
            public short Data3;
            public byte[] Data4 = new byte[8];
        }
    }

    @Structure.FieldOrder({"cbSize", "InterfaceClassGuid", "Flags", "Reserved"})
    public static class SP_DEVICE_INTERFACE_DATA extends Structure {
        public int cbSize;
        public Guid.GUID InterfaceClassGuid;
        public int Flags;
        public Pointer Reserved;
    }

    @Structure.FieldOrder({"cbSize", "DevicePath"})
    public static class SP_DEVICE_INTERFACE_DETAIL_DATA extends Structure {
        public int cbSize;
        public char[] DevicePath = new char[512];
    }

    private interface Hid extends StdCallLibrary {
        Hid INSTANCE = Native.load("hid", Hid.class, W32APIOptions.UNICODE_OPTIONS);
        void HidD_GetHidGuid(Guid.GUID hidGuid);
    }

    private interface SetupApi extends StdCallLibrary {
        SetupApi INSTANCE = Native.load("setupapi", SetupApi.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer SetupDiGetClassDevs(Guid.GUID classGuid, Pointer enumerator, Pointer hwndParent, int flags);
        boolean SetupDiEnumDeviceInterfaces(Pointer deviceInfoSet, Pointer deviceInfoData, Guid.GUID interfaceClassGuid, int memberIndex, SP_DEVICE_INTERFACE_DATA deviceInterfaceData);
        boolean SetupDiGetDeviceInterfaceDetail(Pointer deviceInfoSet, SP_DEVICE_INTERFACE_DATA deviceInterfaceData, SP_DEVICE_INTERFACE_DETAIL_DATA deviceInterfaceDetailData, int deviceInterfaceDetailDataSize, IntByReference requiredSize, Pointer deviceInfoData);
        boolean SetupDiDestroyDeviceInfoList(Pointer deviceInfoSet);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer CreateFile(WString fileName, int desiredAccess, int shareMode, Pointer securityAttributes, int creationDisposition, int flagsAndAttributes, Pointer templateFile);
        boolean WriteFile(Pointer file, byte[] buffer, int bytesToWrite, IntByReference bytesWritten, Pointer overlapped);
        boolean ReadFile(Pointer file, byte[] buffer, int bytesToRead, IntByReference bytesRead, Pointer overlapped);
        boolean CloseHandle(Pointer object);
    }
}

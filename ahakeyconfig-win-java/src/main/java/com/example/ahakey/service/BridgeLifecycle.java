package com.example.ahakey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned handshake and owner-authorized graceful BLE shutdown. */
public final class BridgeLifecycle {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BridgeLifecycle() {}
    public record Info(int protocol, long pid, long parentPid) {}
    public static Info inspect(int port) throws IOException {
        byte[] body = request(port, 9, new byte[0], 0x86);
        var json = JSON.readTree(body);
        int protocol = json.path("protocol").asInt();
        long pid = json.path("pid").asLong(), parent = json.path("parentPid").asLong();
        if (protocol != 2 || pid <= 0) throw new IOException("Incompatible BLE backend");
        return new Info(protocol, pid, parent);
    }
    public static boolean stopOwned(int port, long pid, long parent, String token) {
        try {
            Info info = inspect(port);
            if (info.pid() != pid || info.parentPid() != parent || token == null) return false;
            return JSON.readTree(request(port, 10, token.getBytes(StandardCharsets.UTF_8), 0x85)).path("ok").asBoolean(false);
        } catch (IOException e) { return false; }
    }
    static byte[] request(int port, int type, byte[] body, int expected) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            socket.setSoTimeout(1500);
            var out = socket.getOutputStream();
            out.write(new byte[]{(byte) type, (byte) body.length, (byte)(body.length >>> 8)});
            out.write(body); out.flush();
            var in = socket.getInputStream();
            // Notifications may be broadcast between accepting a client and
            // its query response. Drain bounded frames before the expected one.
            for (int count = 0; count < 8; count++) {
                byte[] header = in.readNBytes(3);
                if (header.length != 3) throw new IOException("Truncated bridge response");
                int size = (header[1] & 255) | ((header[2] & 255) << 8);
                if (size > 8192) throw new IOException("Oversized bridge response");
                byte[] data = in.readNBytes(size);
                if (data.length != size) throw new IOException("Truncated bridge response");
                if ((header[0] & 255) == expected) return data;
            }
            throw new IOException("Missing bridge response");
        }
    }
}

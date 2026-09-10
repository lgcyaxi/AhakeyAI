package com.example.ahakey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Small command-line client for the currently running Studio Hook endpoint. */
public class HookClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 2000;

    public static int run(String event) {
        try {
            String response = exchange(request(event, null));
            if (response != null) {
                // Parse before printing so malformed server responses fail visibly.
                MAPPER.readTree(response);
                System.out.println(response);
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Hook 客户端连接失败: " + e.getMessage());
            return 1;
        }
    }

    public static int sendCommand(String cmd, int value) {
        try {
            String response = exchange(request(cmd, value));
            if (response != null) {
                System.out.println(response);
            }
            return 0;
        } catch (IOException e) {
            System.err.println("发送命令失败: " + e.getMessage());
            return 1;
        }
    }

    public static String queryStatus() {
        try {
            return exchange(request("status", null));
        } catch (IOException e) {
            return "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}";
        }
    }

    private static String request(String command, Integer value) throws IOException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("cmd", command);
        if (value != null) {
            request.put("value", value);
        }
        return MAPPER.writeValueAsString(request);
    }

    private static String exchange(String request) throws IOException {
        HookEndpoint.Descriptor endpoint = HookEndpoint.readLiveCurrent();
        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(endpoint.host(), endpoint.port()),
                CONNECT_TIMEOUT_MS
            );
            socket.setSoTimeout(READ_TIMEOUT_MS);
            try (
                PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                    true
                );
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                )
            ) {
                writer.println(request);
                return reader.readLine();
            }
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

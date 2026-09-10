package com.example.ahakey.service.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Official v3 ASR protocol: https://www.volcengine.com/docs/6561/1354869
 * Binary v1 framing, big-endian lengths, gzip JSON and raw PCM.
 */
public final class DoubaoProtocol {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Result(String text, boolean finalResult) {}
    private DoubaoProtocol() {}
    public static byte[] configuration() throws IOException {
        return frame(1, 0, 1, JSON.writeValueAsBytes(Map.of(
            "user", Map.of("uid", "ahakey-studio"),
            "audio", Map.of("format", "pcm", "codec", "raw", "rate", 16000, "bits", 16, "channel", 1),
            "request", Map.of("model_name", "bigmodel", "enable_itn", true, "enable_punc", true,
                "result_type", "full", "show_utterances", true, "enable_nonstream", true))));
    }
    public static byte[] audio(byte[] pcm, boolean last) throws IOException {
        return frame(2, last ? 2 : 0, 0, pcm);
    }
    private static byte[] frame(int type, int flags, int serialization, byte[] payload) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(payload); }
        byte[] bytes = compressed.toByteArray();
        return ByteBuffer.allocate(8 + bytes.length).put((byte) 0x11)
            .put((byte) ((type << 4) | flags)).put((byte) ((serialization << 4) | 1))
            .put((byte) 0).putInt(bytes.length).put(bytes).array();
    }
    public static Result parse(byte[] frame) throws IOException {
        if (frame.length < 8 || frame.length > MAX_FRAME_BYTES) throw new IOException("豆包响应长度无效");
        ByteBuffer data = ByteBuffer.wrap(frame);
        int versionHeader = Byte.toUnsignedInt(data.get());
        int typeFlags = Byte.toUnsignedInt(data.get());
        int serializationCompression = Byte.toUnsignedInt(data.get());
        data.get();
        int headerBytes = (versionHeader & 15) * 4;
        if ((versionHeader >>> 4) != 1 || headerBytes < 4 || headerBytes > frame.length - 4)
            throw new IOException("豆包协议版本无效");
        data.position(headerBytes);
        int type = typeFlags >>> 4, flags = typeFlags & 15;
        if (type == 15) {
            if (data.remaining() < 8) throw new IOException("豆包错误响应不完整");
            // Never surface the arbitrary server message, which could echo credentials.
            throw new IOException("豆包语音服务错误，代码 " + Integer.toUnsignedString(data.getInt()));
        }
        if (type != 9 || (serializationCompression >>> 4) != 1)
            throw new IOException("豆包响应类型无效");
        if ((flags & 1) != 0) {
            if (data.remaining() < 8) throw new IOException("豆包响应序号不完整");
            data.getInt();
        }
        if (data.remaining() < 4) throw new IOException("豆包响应不完整");
        int length = data.getInt();
        if (length < 0 || length != data.remaining()) throw new IOException("豆包响应长度不匹配");
        byte[] payload = new byte[length]; data.get(payload);
        int compression = serializationCompression & 15;
        if (compression == 1) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                payload = gzip.readNBytes(MAX_FRAME_BYTES + 1);
                if (payload.length > MAX_FRAME_BYTES) throw new IOException("豆包解压响应过大");
            }
        } else if (compression != 0) throw new IOException("豆包响应压缩格式无效");
        JsonNode json;
        try { json = JSON.readTree(payload); }
        catch (IOException e) { throw new IOException("豆包 JSON 响应无效"); }
        if (json == null) throw new IOException("豆包 JSON 响应为空");
        long code = json.path("code").asLong(20000000);
        if (code != 20000000 && code != 0) throw new IOException("豆包语音服务错误，代码 " + code);
        JsonNode result = json.path("result");
        String text;
        if (result.isArray()) {
            StringBuilder aggregate = new StringBuilder();
            for (JsonNode entry : result) aggregate.append(entry.path("text").asText(""));
            text = aggregate.toString();
        } else text = result.path("text").asText("");
        return new Result(text, (flags & 2) != 0);
    }
}

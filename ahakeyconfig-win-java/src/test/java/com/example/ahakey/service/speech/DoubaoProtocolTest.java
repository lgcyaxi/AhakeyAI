package com.example.ahakey.service.speech;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class DoubaoProtocolTest {
    @Test void serializesOfficialConfigurationAndFinalAudioFlags() throws Exception {
        byte[] config = DoubaoProtocol.configuration();
        assertArrayEquals(new byte[] {0x11, 0x10, 0x11, 0}, java.util.Arrays.copyOf(config, 4));
        assertEquals(config.length - 8, ByteBuffer.wrap(config, 4, 4).getInt());
        byte[] payload;
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(config, 8, config.length - 8))) {
            payload = input.readAllBytes();
        }
        var json = new ObjectMapper().readTree(payload);
        assertEquals("pcm", json.path("audio").path("format").asText());
        assertEquals(16000, json.path("audio").path("rate").asInt());
        assertEquals("full", json.path("request").path("result_type").asText());
        assertTrue(json.path("request").path("enable_nonstream").asBoolean());
        assertEquals(0x20, Byte.toUnsignedInt(DoubaoProtocol.audio(new byte[6400], false)[1]));
        assertEquals(0x22, Byte.toUnsignedInt(DoubaoProtocol.audio(new byte[0], true)[1]));
    }
    @Test void mockServiceFramesProducePartialThenFinalResults() throws Exception {
        var partial = DoubaoProtocol.parse(response("{\"result\":{\"text\":\"你好\"}}", false));
        var complete = DoubaoProtocol.parse(response("{\"result\":{\"text\":\"你好世界。\"}}", true));
        assertEquals("你好", partial.text()); assertFalse(partial.finalResult());
        assertEquals("你好世界。", complete.text()); assertTrue(complete.finalResult());
    }
    @Test void rejectsTruncatedOversizeAndServiceErrorsWithoutEchoingMessage() throws Exception {
        assertThrows(IOException.class, () -> DoubaoProtocol.parse(new byte[3]));
        assertThrows(IOException.class, () -> DoubaoProtocol.parse(new byte[DoubaoProtocol.MAX_FRAME_BYTES + 1]));
        byte[] valid = response("{\"result\":{\"text\":\"ok\"}}", true);
        assertThrows(IOException.class, () -> DoubaoProtocol.parse(java.util.Arrays.copyOf(valid, valid.length - 1)));
        byte[] secret = "DO_NOT_ECHO_SECRET".getBytes(StandardCharsets.UTF_8);
        byte[] error = ByteBuffer.allocate(12 + secret.length).put(new byte[] {0x11, (byte) 0xf0, 0x10, 0})
            .putInt(45000001).putInt(secret.length).put(secret).array();
        IOException problem = assertThrows(IOException.class, () -> DoubaoProtocol.parse(error));
        assertTrue(problem.getMessage().contains("45000001"));
        assertFalse(problem.getMessage().contains("SECRET"));
    }
    static byte[] response(String json, boolean last) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
        byte[] payload = bytes.toByteArray();
        return ByteBuffer.allocate(12 + payload.length).put((byte) 0x11).put((byte) (last ? 0x93 : 0x91))
            .put((byte) 0x11).put((byte) 0).putInt(last ? -2 : 1).putInt(payload.length).put(payload).array();
    }
}

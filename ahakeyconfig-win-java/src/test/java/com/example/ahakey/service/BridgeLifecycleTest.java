package com.example.ahakey.service;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
class BridgeLifecycleTest {
    private static byte[] frame(int type, String json) {
        byte[] b=json.getBytes(StandardCharsets.UTF_8), f=new byte[b.length+3];
        f[0]=(byte)type;f[1]=(byte)b.length;f[2]=(byte)(b.length>>>8);
        System.arraycopy(b,0,f,3,b.length);return f;
    }
    @Test void recognizesBackendAndDrainsInterleavedNotifications() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            var received = new java.util.concurrent.CountDownLatch(1);
            var peer=CompletableFuture.runAsync(()->{try(var client=server.accept()){
                assertArrayEquals(new byte[]{9,0,0},client.getInputStream().readNBytes(3));
                var frames = new java.io.ByteArrayOutputStream();
                frames.write(new byte[]{(byte)0x81,0,0});
                frames.write(frame(0x86,"{\"protocol\":2,\"pid\":42,\"parentPid\":24}"));
                client.getOutputStream().write(frames.toByteArray());
                client.getOutputStream().flush();
                assertTrue(received.await(5, java.util.concurrent.TimeUnit.SECONDS));
            }catch(Exception e){throw new RuntimeException(e);}});
            try { assertEquals(new BridgeLifecycle.Info(2,42,24),BridgeLifecycle.inspect(server.getLocalPort())); }
            finally { received.countDown(); }
            peer.get();
        }
    }
    @Test void neverStopsAnotherOwner() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            var peer=CompletableFuture.runAsync(()->{try(var client=server.accept()){
                client.getInputStream().readNBytes(3);
                client.getOutputStream().write(frame(0x86,"{\"protocol\":2,\"pid\":42,\"parentPid\":99}"));
                client.getOutputStream().flush();client.shutdownOutput();
                while(client.getInputStream().read()!=-1) {}
            }catch(Exception e){throw new RuntimeException(e);}});
            assertFalse(BridgeLifecycle.stopOwned(server.getLocalPort(),42,24,"secret"));
            peer.get();server.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class,server::accept);
        }
    }
}

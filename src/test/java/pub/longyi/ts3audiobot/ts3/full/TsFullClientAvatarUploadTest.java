package pub.longyi.ts3audiobot.ts3.full;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsFullClientAvatarUploadTest {

    @Test
    void uploadReturnsTrueWhenAckReportsOk() throws Exception {
        byte[] payload = "avatar-bytes".getBytes(StandardCharsets.UTF_8);
        boolean success = runUploadRoundtrip(payload, socket -> {
            OutputStream out = socket.getOutputStream();
            out.write("error id=0 msg=ok\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
        assertTrue(success);
    }

    @Test
    void uploadReturnsFalseWhenAckReportsError() throws Exception {
        byte[] payload = "avatar-bytes".getBytes(StandardCharsets.UTF_8);
        boolean success = runUploadRoundtrip(payload, socket -> {
            OutputStream out = socket.getOutputStream();
            out.write("error id=2568 msg=insufficient\\sclient\\spermissions\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
        assertFalse(success);
    }

    @Test
    void uploadTreatsConnectionResetAfterPayloadAsSuccess() throws Exception {
        byte[] payload = "avatar-bytes".getBytes(StandardCharsets.UTF_8);
        boolean success = runUploadRoundtrip(payload, socket -> socket.setSoLinger(true, 0));
        assertTrue(success);
    }

    private boolean runUploadRoundtrip(byte[] payload, ServerBehavior behavior) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Void> future = executor.submit(new ServerTask(server, payload, behavior));
            try {
                boolean result = invokeUpload(
                    "127.0.0.1",
                    server.getLocalPort(),
                    "testKey",
                    payload
                );
                future.get(5, TimeUnit.SECONDS);
                return result;
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private boolean invokeUpload(String host, int port, String key, byte[] payload) throws Exception {
        TsFullClient client = new TsFullClient();
        Method method = TsFullClient.class.getDeclaredMethod(
            "uploadFileTransferPayload",
            String.class,
            int.class,
            String.class,
            byte[].class
        );
        method.setAccessible(true);
        return (Boolean) method.invoke(client, host, port, key, payload);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0 || next == '\n') {
                break;
            }
            if (next != '\r') {
                buffer.write(next);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readExact(InputStream input, int len) throws IOException {
        byte[] data = new byte[len];
        int offset = 0;
        while (offset < len) {
            int read = input.read(data, offset, len - offset);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading payload");
            }
            offset += read;
        }
        return data;
    }

    @FunctionalInterface
    private interface ServerBehavior {
        void apply(Socket socket) throws Exception;
    }

    private static class ServerTask implements Callable<Void> {
        private final ServerSocket server;
        private final byte[] expectedPayload;
        private final ServerBehavior behavior;

        private ServerTask(ServerSocket server, byte[] expectedPayload, ServerBehavior behavior) {
            this.server = server;
            this.expectedPayload = expectedPayload;
            this.behavior = behavior;
        }

        @Override
        public Void call() throws IOException, ExecutionException, InterruptedException {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                String header = readLine(socket.getInputStream());
                assertEquals("ftkey=testKey", header);
                byte[] received = readExact(socket.getInputStream(), expectedPayload.length);
                assertArrayEquals(expectedPayload, received);
                try {
                    behavior.apply(socket);
                } catch (Exception ex) {
                    throw new ExecutionException(ex);
                }
            }
            return null;
        }
    }
}

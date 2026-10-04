package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(10)
class ServerTest {

    private Server server;
    private Thread thread;

    private void start(ServerMode mode) throws IOException {
        start(mode.open(0));
    }

    private void start(Server server) {
        this.server = server;
        thread = Thread.ofPlatform().start(() -> {
            try {
                server.serve();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        thread.join();
    }

    private Socket connect() throws IOException {
        var socket = new Socket("localhost", server.port());
        socket.setTcpNoDelay(true);
        // A blocked socket read ignores interrupts, so @Timeout alone can't rescue a test waiting on a reply
        // that never comes.
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static String send(Socket socket, String request, int replyLength) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        return new String(socket.getInputStream().readNBytes(replyLength), StandardCharsets.UTF_8);
    }

    private static String set(String key, String value) {
        return "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$" + value.length() + "\r\n" + value + "\r\n";
    }

    private static String get(String key) {
        return "*2\r\n$3\r\nGET\r\n$" + key.length() + "\r\n" + key + "\r\n";
    }

    @ParameterizedTest
    @EnumSource(ServerMode.class)
    void setAndGetOverTheWire(ServerMode mode) throws IOException {
        start(mode);
        try (var socket = connect()) {
            assertEquals("+OK\r\n", send(socket, set("foo", "bar"), 5));
            assertEquals("$3\r\nbar\r\n", send(socket, get("foo"), 9));
        }
    }

    @ParameterizedTest
    @EnumSource(ServerMode.class)
    void dataSurvivesReconnect(ServerMode mode) throws IOException {
        start(mode);
        try (var socket = connect()) {
            send(socket, set("k", "v"), 5);
        }
        try (var socket = connect()) {
            assertEquals("$1\r\nv\r\n", send(socket, get("k"), 7));
        }
    }

    @ParameterizedTest
    @EnumSource(ServerMode.class)
    void protocolErrorRepliesThenCloses(ServerMode mode) throws IOException {
        start(mode);
        try (var socket = connect()) {
            socket.getOutputStream().write("*1x\r\n".getBytes(StandardCharsets.UTF_8));
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("-ERR Protocol error: invalid length byte 'x'\r\n", reply);
        }
    }

    @ParameterizedTest
    @EnumSource(ServerMode.class)
    void answersPipelinedCommandsInOrder(ServerMode mode) throws IOException {
        start(mode);
        try (var socket = connect()) {
            String batch = "PING\r\n" + set("a", "1") + get("a") + "ECHO hi\r\n";
            assertEquals("+PONG\r\n+OK\r\n$1\r\n1\r\n$2\r\nhi\r\n", send(socket, batch, 27));
        }
    }

    // A 1MB value sent in 1KB pieces: the server sees partial commands and must grow its read buffer.
    @ParameterizedTest
    @EnumSource(ServerMode.class)
    void reassemblesCommandSplitAcrossManyPackets(ServerMode mode) throws IOException {
        start(mode);
        String value = "v".repeat(1024 * 1024);
        byte[] request = set("big", value).getBytes(StandardCharsets.UTF_8);
        try (var socket = connect()) {
            OutputStream out = socket.getOutputStream();
            for (int i = 0; i < request.length; i += 1024) {
                out.write(request, i, Math.min(1024, request.length - i));
                out.flush();
            }
            assertEquals("+OK\r\n", new String(socket.getInputStream().readNBytes(5), StandardCharsets.UTF_8));
            String header = "$" + value.length() + "\r\n";
            assertEquals(header + value + "\r\n", send(socket, get("big"), header.length() + value.length() + 2));
        }
    }

    // The bug the single-threaded server had: one client mid-command must not stall everyone else.
    @ParameterizedTest
    @EnumSource(ServerMode.class)
    void stalledClientDoesNotBlockOthers(ServerMode mode) throws IOException {
        start(mode);
        try (var stalled = connect(); var other = connect()) {
            stalled.getOutputStream().write("*2\r\n$3\r\nGE".getBytes(StandardCharsets.UTF_8));
            assertEquals("+OK\r\n", send(other, set("k", "v"), 5));
            assertEquals("$1\r\nv\r\n", send(stalled, "T\r\n$1\r\nk\r\n", 7));
        }
    }

    @ParameterizedTest
    @EnumSource(ServerMode.class)
    void manyClientsAtOnce(ServerMode mode) throws Exception {
        start(mode);
        int clients = 50;
        int keysEach = 200;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                int id = c;
                done.add(pool.submit(() -> {
                    try (var socket = connect()) {
                        for (int k = 0; k < keysEach; k++) {
                            String key = "c" + id + ":" + k;
                            send(socket, set(key, key), 5);
                        }
                        for (int k = 0; k < keysEach; k++) {
                            String key = "c" + id + ":" + k;
                            String expected = "$" + key.length() + "\r\n" + key + "\r\n";
                            assertEquals(expected, send(socket, get(key), expected.length()));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw new AssertionError(e.getCause());
                }
            }
        }
    }

    @Test
    void survivesRunningOutOfThreads() throws IOException {
        var calls = new AtomicInteger();
        ThreadFactory firstFails = task -> new Thread(task) {
            @Override
            public void start() {
                if (calls.getAndIncrement() == 0) {
                    throw new OutOfMemoryError("unable to create native thread");
                }
                super.start();
            }
        };
        start(new BlockingServer(0, firstFails));
        try (var rejected = connect()) {
            String reply = new String(rejected.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("-ERR max number of clients reached\r\n", reply);
        }
        try (var socket = connect()) {
            assertEquals("+PONG\r\n", send(socket, "PING\r\n", 7));
        }
    }
}

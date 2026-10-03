package io.github.jackfurton.suitandtie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class ServerTest {

    private Server server;
    private Thread thread;

    @BeforeEach
    void start() throws IOException {
        server = new Server(0);
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

    private static String roundTrip(Socket socket, String request, int replyLength) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        InputStream in = socket.getInputStream();
        return new String(in.readNBytes(replyLength), StandardCharsets.UTF_8);
    }

    @Test
    void setAndGetOverTheWire() throws IOException {
        try (var socket = new Socket("localhost", server.port())) {
            assertEquals("+OK\r\n", roundTrip(socket, "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n", 5));
            assertEquals("$3\r\nbar\r\n", roundTrip(socket, "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n", 9));
        }
    }

    @Test
    void dataSurvivesReconnect() throws IOException {
        try (var socket = new Socket("localhost", server.port())) {
            roundTrip(socket, "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n", 5);
        }
        try (var socket = new Socket("localhost", server.port())) {
            assertEquals("$1\r\nv\r\n", roundTrip(socket, "*2\r\n$3\r\nGET\r\n$1\r\nk\r\n", 7));
        }
    }

    @Test
    void protocolErrorRepliesThenCloses() throws IOException {
        try (var socket = new Socket("localhost", server.port())) {
            socket.getOutputStream().write("*1x\r\n".getBytes(StandardCharsets.UTF_8));
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("-ERR Protocol error: invalid length byte 'x'\r\n", reply);
        }
    }
}

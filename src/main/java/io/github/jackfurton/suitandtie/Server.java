package io.github.jackfurton.suitandtie;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;

// One client at a time: a second client waits in the accept backlog until the first disconnects. Fixed in #4.
public final class Server implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Commands commands = new Commands();

    public Server(int port) throws IOException {
        serverSocket = new ServerSocket(port);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void serve() throws IOException {
        while (true) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (SocketException e) {
                if (serverSocket.isClosed()) {
                    return;
                }
                throw e;
            }
            try (client) {
                handle(client);
            } catch (IOException e) {
                System.err.println("client " + client.getRemoteSocketAddress() + ": " + e.getMessage());
            }
        }
    }

    private void handle(Socket client) throws IOException {
        var reader = new RespReader(new BufferedInputStream(client.getInputStream()));
        var writer = new RespWriter(new BufferedOutputStream(client.getOutputStream()));
        while (true) {
            Optional<List<byte[]>> command;
            try {
                command = reader.readCommand();
            } catch (ProtocolException e) {
                // We can't find the start of the next command in a corrupt stream, so reply and hang up like Redis does.
                writer.write(new Reply.Error("ERR Protocol error: " + e.getMessage()));
                writer.flush();
                return;
            }
            if (command.isEmpty()) {
                return;
            }
            writer.write(commands.execute(command.get()));
            writer.flush();
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}

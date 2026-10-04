package io.github.jackfurton.suitandtie;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

/** One thread per client, each blocking in read() until its client sends something. */
public final class BlockingServer implements Server {

    private final ServerSocket serverSocket;
    private final ThreadFactory threads;
    private final Commands commands = new Commands();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();

    public BlockingServer(int port, ThreadFactory threads) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.threads = threads;
    }

    @Override
    public int port() {
        return serverSocket.getLocalPort();
    }

    @Override
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
            client.setTcpNoDelay(true);
            clients.add(client);
            try {
                threads.newThread(() -> handle(client)).start();
            } catch (OutOfMemoryError e) {
                // The OS refused another thread (macOS allows 4096 per process). Turn this one client away
                // rather than let the error kill the accept loop and everyone already connected.
                reject(client);
            }
        }
    }

    private void reject(Socket client) {
        try (client) {
            client.getOutputStream().write("-ERR max number of clients reached\r\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // We're hanging up on them either way.
        } finally {
            clients.remove(client);
        }
    }

    private void handle(Socket client) {
        try (client) {
            var in = new ReadBuffer();
            var writer = new RespWriter(new BufferedOutputStream(client.getOutputStream()));
            while (true) {
                try {
                    List<byte[]> command;
                    while ((command = RespParser.parse(in.data())) != null) {
                        Reply reply;
                        // BytesMap isn't thread-safe: two threads resizing it at once would lose keys.
                        synchronized (commands) {
                            reply = commands.execute(command);
                        }
                        writer.write(reply);
                    }
                } catch (ProtocolException e) {
                    writer.write(new Reply.Error("ERR Protocol error: " + e.getMessage()));
                    writer.flush();
                    return;
                }
                // Flushing once per read, not per command, answers a pipelined batch with one write.
                writer.flush();
                if (in.readFrom(client.getInputStream()) == -1) {
                    return;
                }
            }
        } catch (SocketException e) {
            // Client reset the connection or we're shutting down. Nothing to tell anyone.
        } catch (IOException e) {
            System.err.println("client " + client.getRemoteSocketAddress() + ": " + e);
        } finally {
            clients.remove(client);
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        // Closing a socket is the only way to wake a thread blocked reading from it.
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
                // Already closing; the handler thread will exit either way.
            }
        }
    }
}

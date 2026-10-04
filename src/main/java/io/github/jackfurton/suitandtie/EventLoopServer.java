package io.github.jackfurton.suitandtie;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;

/**
 * One thread serves every client. The Selector (kqueue on macOS, epoll on Linux) tells us which sockets
 * have data waiting, so we never block on any single client. This is Redis's model.
 */
public final class EventLoopServer implements Server {

    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final Commands commands = new Commands();
    private volatile boolean closed;

    public EventLoopServer(int port) throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    @Override
    public int port() {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void serve() throws IOException {
        try {
            while (!closed) {
                selector.select();
                Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
                while (ready.hasNext()) {
                    SelectionKey key = ready.next();
                    ready.remove();
                    if (key.isValid() && key.isAcceptable()) {
                        acceptAll();
                    } else if (key.isValid() && key.attachment() instanceof Client client) {
                        client.onReady();
                    }
                }
            }
        } finally {
            for (SelectionKey key : selector.keys()) {
                key.channel().close();
            }
            selector.close();
        }
    }

    private void acceptAll() throws IOException {
        SocketChannel channel;
        while ((channel = serverChannel.accept()) != null) {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            var client = new Client(channel);
            client.key = channel.register(selector, SelectionKey.OP_READ, client);
        }
    }

    @Override
    public void close() {
        closed = true;
        selector.wakeup();
    }

    private final class Client {

        private final SocketChannel channel;
        private final ReadBuffer in = new ReadBuffer();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final RespWriter writer = new RespWriter(out);
        private SelectionKey key;
        private ByteBuffer unsent;
        private boolean closeWhenSent;

        Client(SocketChannel channel) {
            this.channel = channel;
        }

        void onReady() {
            try {
                if (key.isWritable()) {
                    sendUnsent();
                } else if (key.isReadable()) {
                    read();
                }
            } catch (IOException e) {
                // One broken client must never take down the loop that serves everyone else.
                close();
            }
        }

        private void read() throws IOException {
            if (in.readFrom(channel) == -1) {
                close();
                return;
            }
            try {
                List<byte[]> command;
                while ((command = RespParser.parse(in.data())) != null) {
                    writer.write(commands.execute(command));
                }
            } catch (ProtocolException e) {
                writer.write(new Reply.Error("ERR Protocol error: " + e.getMessage()));
                closeWhenSent = true;
            }
            if (out.size() > 0) {
                unsent = ByteBuffer.wrap(out.toByteArray());
                out.reset();
                sendUnsent();
            }
        }

        // The socket's send buffer can fill up if the client isn't reading. Then we stop reading from it
        // (so it can't make us queue unbounded replies) and wait for the Selector to say it's writable.
        private void sendUnsent() throws IOException {
            channel.write(unsent);
            if (unsent.hasRemaining()) {
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
            unsent = null;
            if (closeWhenSent) {
                close();
            } else {
                key.interestOps(SelectionKey.OP_READ);
            }
        }

        private void close() {
            key.cancel();
            try {
                channel.close();
            } catch (IOException ignored) {
                // Nothing left to clean up.
            }
        }
    }
}

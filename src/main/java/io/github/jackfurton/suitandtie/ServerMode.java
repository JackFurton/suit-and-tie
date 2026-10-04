package io.github.jackfurton.suitandtie;

import java.io.IOException;

public enum ServerMode {
    THREADS,
    VIRTUAL,
    EVENTLOOP;

    public Server open(int port) throws IOException {
        return switch (this) {
            case THREADS -> new BlockingServer(port, Thread.ofPlatform().factory());
            case VIRTUAL -> new BlockingServer(port, Thread.ofVirtual().factory());
            case EVENTLOOP -> new EventLoopServer(port);
        };
    }
}

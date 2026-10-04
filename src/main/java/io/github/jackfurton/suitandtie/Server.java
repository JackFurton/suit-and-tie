package io.github.jackfurton.suitandtie;

import java.io.IOException;

public interface Server extends AutoCloseable {

    int port();

    /** Blocks until close() is called. */
    void serve() throws IOException;

    @Override
    void close() throws IOException;
}

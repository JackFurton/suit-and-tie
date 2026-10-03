package io.github.jackfurton.suitandtie;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6379;
        try (var server = new Server(port)) {
            System.out.println("listening on " + server.port());
            server.serve();
        }
    }
}

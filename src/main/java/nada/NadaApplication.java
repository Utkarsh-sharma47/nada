package nada;

import nada.http.ApiServer;

import java.io.IOException;

public final class NadaApplication {

    private NadaApplication() {
    }

    public static void main(String[] args) {
        final int port;
        try {
            port = resolvePort(args, System.getenv("PORT"));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
            return;
        }

        final ApiServer server;
        try {
            server = ApiServer.start(port);
        } catch (IOException exception) {
            System.err.println("Could not start server on port " + port + ": " + exception.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "nada-shutdown"));
        int listening = server.port();
        System.out.println("Nada server listening on http://localhost:" + listening);
        System.out.println("UI:  http://localhost:" + listening + "/");
        System.out.println("API: http://localhost:" + listening);
    }

    /**
     * The first argument wins, then the {@code PORT} environment variable, then {@link Limits#DEFAULT_PORT}.
     */
    static int resolvePort(String[] args, String environmentPort) {
        String raw = null;
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            raw = args[0];
        } else if (environmentPort != null && !environmentPort.isBlank()) {
            raw = environmentPort;
        }
        if (raw == null) {
            return Limits.DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port must be an integer between 0 and 65535");
        }
    }
}

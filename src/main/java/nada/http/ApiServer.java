package nada.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nada.Limits;
import nada.error.ApiException;
import nada.error.BadRequestException;
import nada.error.NotFoundException;
import nada.model.ErrorBody;
import nada.model.Person;
import nada.repository.InMemoryNetworkRepository;
import nada.service.ConnectionService;
import nada.service.PathService;
import nada.service.PersonService;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK HTTP server for the four assignment endpoints. Invalid application input becomes a JSON
 * error response and does not stop the process.
 */
public final class ApiServer {

    private static final Pattern CONTACTS = Pattern.compile("^/people/([^/]+)/contacts$");
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    private final HttpServer server;
    private final ExecutorService executor;
    private final PersonService people;
    private final ConnectionService connections;
    private final PathService paths;

    private ApiServer(
            HttpServer server,
            ExecutorService executor,
            PersonService people,
            ConnectionService connections,
            PathService paths
    ) {
        this.server = server;
        this.executor = executor;
        this.people = people;
        this.connections = connections;
        this.paths = paths;
    }

    public static ApiServer start(int port) throws IOException {
        InMemoryNetworkRepository repository = new InMemoryNetworkRepository();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "nada-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        ApiServer api = new ApiServer(
                server,
                executor,
                new PersonService(repository),
                new ConnectionService(repository),
                new PathService(repository)
        );
        server.createContext("/", api::handle);
        server.start();
        return api;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try {
            route(exchange);
        } catch (ApiException exception) {
            writeError(exchange, exception.status(), exception.getMessage());
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            writeError(exchange, 500, "internal error");
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = normalize(exchange.getRequestURI().getPath());

        if ("/people".equals(path)) {
            if (!"POST".equals(method)) {
                throw new ApiException(405, "method not allowed");
            }
            createPerson(exchange);
            return;
        }
        if ("/knows".equals(path)) {
            if (!"POST".equals(method)) {
                throw new ApiException(405, "method not allowed");
            }
            createConnection(exchange);
            return;
        }
        if ("/path".equals(path)) {
            if (!"GET".equals(method)) {
                throw new ApiException(405, "method not allowed");
            }
            findPath(exchange);
            return;
        }

        Matcher contacts = CONTACTS.matcher(path);
        if (contacts.matches()) {
            if (!"GET".equals(method)) {
                throw new ApiException(405, "method not allowed");
            }
            listContacts(exchange, contacts.group(1));
            return;
        }
        throw NotFoundException.route();
    }

    private void createPerson(HttpExchange exchange) throws IOException {
        JsonNode body = JsonCodec.readObject(readBody(exchange));
        JsonNode nameNode = body.get("name");
        if (nameNode == null || nameNode.isNull()) {
            throw new BadRequestException("name is required");
        }
        if (!nameNode.isTextual()) {
            throw new BadRequestException("name must be a string");
        }
        Person person = people.create(nameNode.asText());
        writeJson(exchange, 201, person);
    }

    private void createConnection(HttpExchange exchange) throws IOException {
        JsonNode body = JsonCodec.readObject(readBody(exchange));
        long a = jsonInteger(body, "a");
        long b = jsonInteger(body, "b");
        connections.connect(a, b);
        writeNoContent(exchange);
    }

    private void listContacts(HttpExchange exchange, String rawId) throws IOException {
        long id = parseInteger(rawId, "person id");
        List<Person> contacts = connections.contacts(id);
        writeJson(exchange, 200, contacts);
    }

    private void findPath(HttpExchange exchange) throws IOException {
        Map<String, String> query = queryParameters(exchange.getRequestURI().getRawQuery());
        long from = queryInteger(query, "from");
        long to = queryInteger(query, "to");
        writeJson(exchange, 200, paths.shortestPath(from, to));
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lengthHeader != null) {
            try {
                if (Long.parseLong(lengthHeader.trim()) < 0) {
                    throw new BadRequestException("invalid Content-Length");
                }
            } catch (NumberFormatException exception) {
                throw new BadRequestException("invalid Content-Length");
            }
        }
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(Limits.MAX_BODY_BYTES + 1);
            if (body.length > Limits.MAX_BODY_BYTES) {
                throw new BadRequestException("request body is too large");
            }
            return body;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BadRequestException("could not read request body");
        }
    }

    private static long jsonInteger(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) {
            throw new BadRequestException(field + " is required");
        }
        JsonNode node = body.get(field);
        if (!node.isNumber()) {
            throw new BadRequestException(field + " must be an integer");
        }
        JsonParser.NumberType type = node.numberType();
        boolean wholeNumber = type == JsonParser.NumberType.INT
                || type == JsonParser.NumberType.LONG
                || type == JsonParser.NumberType.BIG_INTEGER;
        if (!wholeNumber || !node.canConvertToLong()) {
            throw new BadRequestException(field + " must be an integer");
        }
        return node.longValue();
    }

    private static long queryInteger(Map<String, String> query, String name) {
        if (!query.containsKey(name) || query.get(name).isBlank()) {
            throw new BadRequestException(name + " is required");
        }
        return parseInteger(query.get(name), name);
    }

    private static long parseInteger(String raw, String name) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!INTEGER.matcher(trimmed).matches()) {
            throw new BadRequestException(name + " must be an integer");
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException exception) {
            throw new BadRequestException(name + " must be an integer");
        }
    }

    private static Map<String, String> queryParameters(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String part : rawQuery.split("&", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            int separator = part.indexOf('=');
            String key;
            String value;
            if (separator < 0) {
                key = decode(part);
                value = "";
            } else {
                key = decode(part.substring(0, separator));
                value = decode(part.substring(separator + 1));
            }
            parameters.putIfAbsent(key, value);
        }
        return parameters;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("malformed query");
        }
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JsonCodec.write(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void writeNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void writeError(HttpExchange exchange, int status, String message) {
        try {
            if (status == 400 && "request body is too large".equals(message)) {
                exchange.getResponseHeaders().set("Connection", "close");
            }
            writeJson(exchange, status, new ErrorBody(message));
        } catch (Exception exception) {
            exchange.close();
        }
    }
}

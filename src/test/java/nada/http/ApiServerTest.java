package nada.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nada.Limits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    private static final ObjectMapper JSON = JsonCodec.mapper();

    private ApiServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = ApiServer.start(0);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Nested
    class People {

        @Test
        void createsAPersonWithAServerAssignedId() throws Exception {
            HttpResult created = post("/people", "{\"name\":\"Asha\"}");

            assertEquals(201, created.status);
            assertEquals("application/json; charset=UTF-8", created.contentType);
            assertEquals(1L, created.body.get("id").asLong());
            assertTrue(created.body.get("id").isNumber());
            assertEquals("Asha", created.body.get("name").asText());
        }

        @Test
        void assignsUniqueIdsAndIgnoresAClientSuppliedId() throws Exception {
            HttpResult first = post("/people", "{\"id\":99,\"name\":\"Asha\"}");
            HttpResult second = post("/people", "{\"name\":\"Rohan\"}");

            assertEquals(1L, first.body.get("id").asLong());
            assertEquals(2L, second.body.get("id").asLong());
            assertEquals("Rohan", second.body.get("name").asText());
        }

        @Test
        void preservesUnicodeAndSurroundingSpaces() throws Exception {
            String name = "  आशा  ";
            HttpResult created = post("/people", "{\"name\":" + JSON.writeValueAsString(name) + "}");

            assertEquals(201, created.status);
            assertEquals(name, created.body.get("name").asText());
        }

        @Test
        void rejectsMissingBlankAndNonStringNames() throws Exception {
            assertError(400, "name is required", post("/people", "{}"));
            assertError(400, "name is required", post("/people", "{\"name\":null}"));
            assertError(400, "name must be a string", post("/people", "{\"name\":1}"));
            assertError(400, "name must not be blank", post("/people", "{\"name\":\"\"}"));
            assertError(400, "name must not be blank", post("/people", "{\"name\":\"  \"}"));
            assertError(400, "name must not be blank", post("/people", "{\"name\":\"\\n\\t\"}"));
        }

        @Test
        void rejectsMalformedJsonWithoutLosingLaterRequests() throws Exception {
            assertError(400, "invalid JSON", post("/people", "{"));
            assertError(400, "invalid JSON", post("/people", ""));
            assertError(400, "invalid JSON", post("/people", "[]"));
            assertError(400, "invalid JSON", post("/people", "null"));

            HttpResult created = post("/people", "{\"name\":\"Asha\"}");
            assertEquals(201, created.status);
            assertEquals(1L, created.body.get("id").asLong());
        }
    }

    @Nested
    class Connections {

        @Test
        void createsAMutualIdempotentConnection() throws Exception {
            post("/people", "{\"name\":\"Asha\"}");
            post("/people", "{\"name\":\"Rohan\"}");

            HttpResult first = post("/knows", "{\"a\":1,\"b\":2}");
            HttpResult repeat = post("/knows", "{\"a\":1,\"b\":2}");
            HttpResult reversed = post("/knows", "{\"b\":1,\"a\":2}");

            assertEquals(204, first.status);
            assertEquals("", first.rawBody);
            assertEquals(204, repeat.status);
            assertEquals(204, reversed.status);

            JsonNode asha = get("/people/1/contacts").body;
            JsonNode rohan = get("/people/2/contacts").body;
            assertEquals(1, asha.size());
            assertEquals(2L, asha.get(0).get("id").asLong());
            assertEquals("Rohan", asha.get(0).get("name").asText());
            assertEquals(1, rohan.size());
            assertEquals(1L, rohan.get(0).get("id").asLong());
            assertEquals("Asha", rohan.get(0).get("name").asText());
        }

        @Test
        void rejectsSelfConnectionsAndDoesNotStoreThem() throws Exception {
            post("/people", "{\"name\":\"Asha\"}");

            assertError(400, "a person cannot be connected to themselves", post("/knows", "{\"a\":1,\"b\":1}"));
            assertError(400, "a person cannot be connected to themselves", post("/knows", "{\"a\":9,\"b\":9}"));
            assertEquals(0, get("/people/1/contacts").body.size());
        }

        @Test
        void rejectsMissingMalformedAndUnknownIds() throws Exception {
            post("/people", "{\"name\":\"Asha\"}");

            assertError(400, "invalid JSON", post("/knows", "{"));
            assertError(400, "b is required", post("/knows", "{\"a\":1}"));
            assertError(400, "a is required", post("/knows", "{\"b\":2}"));
            assertError(400, "a must be an integer", post("/knows", "{\"a\":\"1\",\"b\":2}"));
            assertError(400, "a must be an integer", post("/knows", "{\"a\":true,\"b\":2}"));
            assertError(400, "a must be an integer", post("/knows", "{\"a\":1.5,\"b\":2}"));
            assertError(400, "b must be an integer", post("/knows", "{\"a\":1,\"b\":1.0}"));
            assertError(404, "no person 2", post("/knows", "{\"a\":1,\"b\":2}"));
            assertError(404, "no person 8", post("/knows", "{\"a\":8,\"b\":9}"));
            assertEquals(0, get("/people/1/contacts").body.size());
        }
    }

    @Nested
    class Contacts {

        @Test
        void returnsOnlyDirectContactsInFirstSeenOrder() throws Exception {
            post("/people", "{\"name\":\"Asha\"}");
            post("/people", "{\"name\":\"Rohan\"}");
            post("/people", "{\"name\":\"Meera\"}");
            post("/knows", "{\"a\":1,\"b\":2}");
            post("/knows", "{\"a\":2,\"b\":3}");

            JsonNode contacts = get("/people/1/contacts").body;
            assertEquals(200, get("/people/1/contacts").status);
            assertEquals(1, contacts.size());
            assertEquals("Rohan", contacts.get(0).get("name").asText());
            assertEquals("Asha", get("/people/2/contacts").body.get(0).get("name").asText());
            assertEquals("Meera", get("/people/2/contacts").body.get(1).get("name").asText());
        }

        @Test
        void returnsAnEmptyArrayForAPersonWithNoContacts() throws Exception {
            post("/people", "{\"name\":\"Asha\"}");
            HttpResult contacts = get("/people/1/contacts");

            assertEquals(200, contacts.status);
            assertTrue(contacts.body.isArray());
            assertEquals(0, contacts.body.size());
            assertEquals("application/json; charset=UTF-8", contacts.contentType);
        }

        @Test
        void returns404ForAnUnknownPersonAnd400ForABadId() throws Exception {
            assertError(404, "no person 9", get("/people/9/contacts"));
            assertError(400, "person id must be an integer", get("/people/abc/contacts"));
            assertError(400, "person id must be an integer", get("/people/1.5/contacts"));
        }

        @Test
        void staysUnchangedWhenTheSameConnectionIsRepeated() throws Exception {
            post("/people", "{\"name\":\"Asha\"}");
            post("/people", "{\"name\":\"Rohan\"}");
            post("/knows", "{\"a\":1,\"b\":2}");
            post("/knows", "{\"a\":2,\"b\":1}");
            post("/knows", "{\"a\":1,\"b\":2}");

            assertEquals(1, get("/people/1/contacts").body.size());
            assertEquals(1, get("/people/2/contacts").body.size());
        }
    }

    @Nested
    class Paths {

        @Test
        void returnsTheShortestPathOnBothSidesOfTheThreeHopLimit() throws Exception {
            assertEquals(3, Limits.MAX_HOPS);
            long asha = create("Asha");
            long b = create("B");
            long c = create("C");
            long d = create("D");
            long e = create("E");
            know(asha, b);
            know(b, c);
            know(c, d);
            know(d, e);

            HttpResult three = get("/path?from=" + asha + "&to=" + d);
            assertEquals(200, three.status);
            assertTrue(three.body.get("connected").asBoolean());
            assertEquals(3, three.body.get("hops").asInt());
            assertEquals(List.of("Asha", "B", "C", "D"), names(three.body.get("path")));

            HttpResult four = get("/path?from=" + asha + "&to=" + e);
            assertEquals(200, four.status);
            assertFalse(four.body.get("connected").asBoolean());
            assertEquals(Set.of("connected"), fieldNames(four.body));
        }

        @Test
        void returnsOneHopAndTwoHopPaths() throws Exception {
            long asha = create("Asha");
            long rohan = create("Rohan");
            long meera = create("Meera");
            know(asha, rohan);
            know(rohan, meera);

            HttpResult direct = get("/path?from=1&to=2");
            assertEquals(1, direct.body.get("hops").asInt());
            assertEquals(List.of("Asha", "Rohan"), names(direct.body.get("path")));

            HttpResult two = get("/path?from=1&to=3");
            assertEquals(2, two.body.get("hops").asInt());
            assertEquals(List.of("Asha", "Rohan", "Meera"), names(two.body.get("path")));
        }

        @Test
        void prefersAShorterPathWhenALongerOneAlsoExists() throws Exception {
            long a = create("A");
            long b = create("B");
            long c = create("C");
            long d = create("D");
            long e = create("E");
            know(a, b);
            know(b, c);
            know(c, d);
            know(d, e);
            know(a, e);

            HttpResult result = get("/path?from=" + a + "&to=" + e);
            assertEquals(1, result.body.get("hops").asInt());
            assertEquals(List.of("A", "E"), names(result.body.get("path")));
        }

        @Test
        void returnsAnyOneOfSeveralShortestPaths() throws Exception {
            long a = create("A");
            long b = create("B");
            long c = create("C");
            long d = create("D");
            know(a, b);
            know(a, c);
            know(b, d);
            know(c, d);

            HttpResult result = get("/path?from=" + a + "&to=" + d);
            assertEquals(2, result.body.get("hops").asInt());
            List<String> path = names(result.body.get("path"));
            assertEquals("A", path.get(0));
            assertEquals("D", path.get(path.size() - 1));
            assertTrue(path.get(1).equals("B") || path.get(1).equals("C"));
        }

        @Test
        void returnsAZeroHopPathForTheSamePerson() throws Exception {
            create("Asha");
            HttpResult result = get("/path?from=1&to=1");

            assertEquals(200, result.status);
            assertTrue(result.body.get("connected").asBoolean());
            assertEquals(0, result.body.get("hops").asInt());
            assertEquals(1, result.body.get("path").size());
            assertEquals("Asha", result.body.get("path").get(0).get("name").asText());
        }

        @Test
        void returnsNotConnectedForDisconnectedPeopleAndIsolatedVertices() throws Exception {
            create("Asha");
            create("Rohan");

            HttpResult disconnected = get("/path?from=1&to=2");
            assertEquals(200, disconnected.status);
            assertFalse(disconnected.body.get("connected").asBoolean());
            assertEquals(Set.of("connected"), fieldNames(disconnected.body));
            assertEquals(0, get("/path?from=2&to=2").body.get("hops").asInt());
        }

        @Test
        void finishesWhenTheGraphContainsACycle() throws Exception {
            long a = create("A");
            long b = create("B");
            long c = create("C");
            long d = create("D");
            long e = create("E");
            long f = create("F");
            long g = create("G");
            know(a, b);
            know(b, c);
            know(c, a);
            know(c, d);
            know(d, e);
            know(e, f);
            know(f, g);

            HttpResult result = assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> get("/path?from=" + a + "&to=" + g)
            );
            assertFalse(result.body.get("connected").asBoolean());
            assertEquals(3, get("/path?from=" + a + "&to=" + e).body.get("hops").asInt());
        }

        @Test
        void rejectsUnknownEndpointsAndMalformedQueryParameters() throws Exception {
            create("Asha");

            assertError(404, "no person 5", get("/path?from=1&to=5"));
            assertError(404, "no person 8", get("/path?from=8&to=1"));
            assertError(404, "no person 8", get("/path?from=8&to=9"));
            assertError(400, "from is required", get("/path"));
            assertError(400, "from is required", get("/path?to=1"));
            assertError(400, "to is required", get("/path?from=1"));
            assertError(400, "from is required", get("/path?from=&to=1"));
            assertError(400, "to is required", get("/path?from=1&to="));
            assertError(400, "from must be an integer", get("/path?from=abc&to=1"));
            assertError(400, "to must be an integer", get("/path?from=1&to=1.5"));
            assertError(400, "from must be an integer", get("/path?from=999999999999999999999&to=1"));

            // The JDK HTTP server rejects an illegal request-target before the application
            // can write a JSON body. The status is still 400 and the process stays up.
            String raw = rawHttp("GET /path?from=%ZZ&to=1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(raw.startsWith("HTTP/1.1 400"), raw);
            assertEquals(201, post("/people", "{\"name\":\"Rohan\"}").status);
        }
    }

    @Nested
    class Reliability {

        @Test
        void keepsStoredStateWhenRequestsFail() throws Exception {
            assertEquals(201, post("/people", "{\"name\":\"Asha\"}").status);
            assertEquals(400, post("/people", "{\"name\":\"\"}").status);
            assertEquals(400, post("/knows", "{\"a\":1,\"b\":1}").status);
            assertEquals(404, post("/knows", "{\"a\":1,\"b\":2}").status);
            assertEquals(400, get("/path?from=nope&to=1").status);

            HttpResult second = post("/people", "{\"name\":\"Rohan\"}");
            assertEquals(2L, second.body.get("id").asLong());
            assertEquals(0, get("/people/1/contacts").body.size());
            assertFalse(get("/path?from=1&to=2").body.get("connected").asBoolean());
        }

        @Test
        void staysUpAfterASeriesOfBadRequests() throws Exception {
            for (int i = 0; i < 20; i++) {
                assertEquals(400, post("/people", "{bad").status);
            }
            assertEquals(201, post("/people", "{\"name\":\"Asha\"}").status);
            assertEquals(404, get("/no-such-route").status);
            assertEquals(405, post("/path", "{}").status);
        }

        @Test
        void allocatesUniqueIdsAndOneEdgeUnderConcurrentRequests() throws Exception {
            int writers = 12;
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            try {
                List<Future<?>> creates = new ArrayList<>();
                for (int i = 0; i < writers; i++) {
                    creates.add(pool.submit(() -> {
                        start.await();
                        HttpResult created = post("/people", "{\"name\":\"Person\"}");
                        ids.add(created.body.get("id").asLong());
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : creates) {
                    future.get();
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(writers, ids.size());

            post("/knows", "{\"a\":1,\"b\":2}");
            ExecutorService connectors = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> repeats = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    String body = i % 2 == 0 ? "{\"a\":1,\"b\":2}" : "{\"a\":2,\"b\":1}";
                    repeats.add(connectors.submit(() -> {
                        assertEquals(204, post("/knows", body).status);
                        return null;
                    }));
                }
                for (Future<?> future : repeats) {
                    future.get();
                }
            } finally {
                connectors.shutdownNow();
            }
            assertEquals(1, get("/people/1/contacts").body.size());
            assertEquals(1, get("/people/2/contacts").body.size());
        }

        @Test
        void rejectsAnOversizedBody() throws Exception {
            byte[] payload = new byte[Limits.MAX_BODY_BYTES + 1];
            HttpRequest request = HttpRequest.newBuilder(uri("/people"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(400, response.statusCode());
            assertEquals("request body is too large", JSON.readTree(response.body()).get("error").asText());
            assertEquals(201, post("/people", "{\"name\":\"Asha\"}").status);
        }
    }

    private long create(String name) throws Exception {
        return post("/people", "{\"name\":" + JSON.writeValueAsString(name) + "}").body.get("id").asLong();
    }

    private void know(long a, long b) throws Exception {
        assertEquals(204, post("/knows", "{\"a\":" + a + ",\"b\":" + b + "}").status);
    }

    private HttpResult post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private HttpResult get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return send(request);
    }

    private HttpResult send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode parsed = response.body() == null || response.body().isEmpty()
                ? null
                : JSON.readTree(response.body());
        String contentType = response.headers().firstValue("content-type").orElse("");
        return new HttpResult(response.statusCode(), response.body(), contentType, parsed);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private String rawHttp(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertError(int status, String message, HttpResult result) {
        assertEquals(status, result.status);
        assertEquals("application/json; charset=UTF-8", result.contentType);
        assertEquals(message, result.body.get("error").asText());
    }

    private static List<String> names(JsonNode path) {
        List<String> names = new ArrayList<>();
        path.forEach(person -> names.add(person.get("name").asText()));
        return names;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = ConcurrentHashMap.newKeySet();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private record HttpResult(int status, String rawBody, String contentType, JsonNode body) {
    }
}

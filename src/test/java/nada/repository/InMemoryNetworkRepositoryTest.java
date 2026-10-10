package nada.repository;

import nada.model.Person;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryNetworkRepositoryTest {

    private final InMemoryNetworkRepository repository = new InMemoryNetworkRepository();

    @Test
    void assignsUniqueIdsStartingAtOne() {
        Person first = repository.insert("Asha");
        Person second = repository.insert("Rohan");

        assertEquals(1L, first.id());
        assertEquals(2L, second.id());
        assertEquals("Asha", repository.find(1L).orElseThrow().name());
    }

    @Test
    void storesAnUndirectedEdgeOnceInEitherDirection() {
        repository.insert("Asha");
        repository.insert("Rohan");

        assertTrue(repository.connect(1L, 2L));
        assertTrue(repository.connect(1L, 2L));
        assertTrue(repository.connect(2L, 1L));

        assertEquals(List.of(2L), ids(repository.contactsOf(1L).orElseThrow()));
        assertEquals(List.of(1L), ids(repository.contactsOf(2L).orElseThrow()));
    }

    @Test
    void keepsContactOrderAndDoesNotInventAMissingEdge() {
        repository.insert("Asha");
        repository.insert("Rohan");
        repository.insert("Meera");

        repository.connect(1L, 3L);
        repository.connect(1L, 2L);

        assertEquals(List.of(3L, 2L), ids(repository.contactsOf(1L).orElseThrow()));
        assertEquals(List.of(1L), ids(repository.contactsOf(2L).orElseThrow()));
        assertFalse(repository.connect(1L, 9L));
        assertFalse(repository.connect(8L, 9L));
        assertTrue(repository.contactsOf(9L).isEmpty());
        assertEquals(List.of(1L), ids(repository.contactsOf(2L).orElseThrow()));
    }

    @Test
    void allocatesUniqueIdsUnderConcurrentInserts() throws Exception {
        int threads = 16;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        ids.add(repository.insert("p").id());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, ids.size());
    }

    private static List<Long> ids(List<Person> people) {
        return people.stream().map(Person::id).toList();
    }
}

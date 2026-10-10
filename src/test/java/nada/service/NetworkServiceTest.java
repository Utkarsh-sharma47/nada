package nada.service;

import nada.Limits;
import nada.error.BadRequestException;
import nada.error.NotFoundException;
import nada.model.PathResult;
import nada.model.Person;
import nada.repository.InMemoryNetworkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkServiceTest {

    private InMemoryNetworkRepository repository;
    private PersonService people;
    private ConnectionService connections;
    private PathService paths;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNetworkRepository();
        people = new PersonService(repository);
        connections = new ConnectionService(repository);
        paths = new PathService(repository);
    }

    @Test
    void rejectsBlankNamesAndPreservesTheSuppliedText() {
        assertThrows(BadRequestException.class, () -> people.create(null));
        assertThrows(BadRequestException.class, () -> people.create(""));
        assertThrows(BadRequestException.class, () -> people.create(" \n\t "));

        Person person = people.create("  आशा  ");
        assertEquals(1L, person.id());
        assertEquals("  आशा  ", person.name());
    }

    @Test
    void rejectsSelfConnectionsAndUnknownPeopleWithoutWritingAnEdge() {
        people.create("Asha");
        assertThrows(BadRequestException.class, () -> connections.connect(1L, 1L));
        assertThrows(BadRequestException.class, () -> connections.connect(9L, 9L));
        assertThrows(NotFoundException.class, () -> connections.connect(1L, 2L));
        assertEquals(List.of(), connections.contacts(1L));
        assertThrows(NotFoundException.class, () -> connections.contacts(2L));
    }

    @Test
    void findsPathsInsideTheHopLimitAndRejectsLongerChains() {
        assertEquals(3, Limits.MAX_HOPS);
        long asha = people.create("Asha").id();
        long b = people.create("B").id();
        long c = people.create("C").id();
        long d = people.create("D").id();
        long e = people.create("E").id();
        connections.connect(asha, b);
        connections.connect(b, c);
        connections.connect(c, d);
        connections.connect(d, e);

        PathResult three = paths.shortestPath(asha, d);
        assertTrue(three.connected());
        assertEquals(3, three.hops());
        assertEquals(List.of(asha, b, c, d), three.path().stream().map(Person::id).toList());

        PathResult four = paths.shortestPath(asha, e);
        assertFalse(four.connected());
        assertNull(four.hops());
        assertNull(four.path());

        PathResult self = paths.shortestPath(asha, asha);
        assertTrue(self.connected());
        assertEquals(0, self.hops());
        assertEquals(List.of("Asha"), self.path().stream().map(Person::name).toList());
    }

    @Test
    void prefersAShorterPathAndStopsOnCycles() {
        long a = people.create("A").id();
        long b = people.create("B").id();
        long c = people.create("C").id();
        long d = people.create("D").id();
        connections.connect(a, b);
        connections.connect(b, c);
        connections.connect(c, d);
        connections.connect(a, d);

        PathResult shortest = paths.shortestPath(a, d);
        assertEquals(1, shortest.hops());
        assertEquals(List.of(a, d), shortest.path().stream().map(Person::id).toList());

        long e = people.create("E").id();
        long f = people.create("F").id();
        long g = people.create("G").id();
        connections.connect(c, a);
        connections.connect(d, e);
        connections.connect(e, f);
        connections.connect(f, g);

        PathResult blocked = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> paths.shortestPath(a, g));
        assertFalse(blocked.connected());
        assertThrows(NotFoundException.class, () -> paths.shortestPath(a, 99L));
        assertThrows(NotFoundException.class, () -> paths.shortestPath(99L, a));
    }

    @Test
    void acceptsEitherShortestPathWhenSeveralExist() {
        long a = people.create("A").id();
        long b = people.create("B").id();
        long c = people.create("C").id();
        long d = people.create("D").id();
        connections.connect(a, b);
        connections.connect(a, c);
        connections.connect(b, d);
        connections.connect(c, d);

        PathResult result = paths.shortestPath(a, d);
        assertEquals(2, result.hops());
        List<Long> ids = result.path().stream().map(Person::id).toList();
        assertEquals(a, ids.get(0));
        assertEquals(d, ids.get(3 - 1));
        assertTrue(ids.get(1) == b || ids.get(1) == c);
    }

    @Test
    void reportsDisconnectedPeopleAndIsolatedVertices() {
        long a = people.create("A").id();
        long b = people.create("B").id();
        assertFalse(paths.shortestPath(a, b).connected());
        assertEquals(0, paths.shortestPath(b, b).hops());
        assertEquals(List.of(), connections.contacts(a));
    }
}

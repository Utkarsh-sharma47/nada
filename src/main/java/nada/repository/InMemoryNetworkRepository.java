package nada.repository;

import nada.model.Person;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Process-local store of people and undirected connections.
 *
 * <p>One lock guards the person map and the adjacency sets together, so a connection is always
 * mutual and concurrent requests cannot observe a half-written edge. Person ids start at 1 and
 * are never reused. Contact lists keep the order in which each connection was first recorded.
 */
public final class InMemoryNetworkRepository {

    private final Object lock = new Object();
    private final Map<Long, Person> people = new HashMap<>();
    private final Map<Long, LinkedHashSet<Long>> contacts = new HashMap<>();
    private long nextId = 0;

    private final GraphView view = new GraphView() {
        @Override
        public boolean contains(long id) {
            return people.containsKey(id);
        }

        @Override
        public Person person(long id) {
            return people.get(id);
        }

        @Override
        public List<Long> neighborIds(long id) {
            LinkedHashSet<Long> neighbors = contacts.get(id);
            if (neighbors == null) {
                return List.of();
            }
            return List.copyOf(neighbors);
        }
    };

    public Person insert(String name) {
        synchronized (lock) {
            long id = ++nextId;
            Person person = new Person(id, name);
            people.put(id, person);
            contacts.put(id, new LinkedHashSet<>());
            return person;
        }
    }

    public Optional<Person> find(long id) {
        synchronized (lock) {
            return Optional.ofNullable(people.get(id));
        }
    }

    /**
     * Records an undirected connection. Repeating the same pair, in either direction, leaves the
     * graph unchanged. When either person is missing, no edge is written and the returned id is
     * the one that was missing, with {@code a} preferred if both were. That choice is made under
     * the same lock as the update, so a concurrent create cannot change which id is reported.
     */
    public OptionalLong connect(long a, long b) {
        synchronized (lock) {
            if (!people.containsKey(a)) {
                return OptionalLong.of(a);
            }
            if (!people.containsKey(b)) {
                return OptionalLong.of(b);
            }
            contacts.get(a).add(b);
            contacts.get(b).add(a);
            return OptionalLong.empty();
        }
    }

    public Optional<List<Person>> contactsOf(long id) {
        synchronized (lock) {
            LinkedHashSet<Long> neighborIds = contacts.get(id);
            if (neighborIds == null) {
                return Optional.empty();
            }
            List<Person> neighbors = new ArrayList<>(neighborIds.size());
            for (Long neighborId : neighborIds) {
                Person neighbor = people.get(neighborId);
                if (neighbor == null) {
                    throw new IllegalStateException("contact " + neighborId + " is missing from the person store");
                }
                neighbors.add(neighbor);
            }
            return Optional.of(List.copyOf(neighbors));
        }
    }

    public <T> T read(Function<GraphView, T> query) {
        synchronized (lock) {
            return query.apply(view);
        }
    }
}

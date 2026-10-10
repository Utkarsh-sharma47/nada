package nada.service;

import nada.Limits;
import nada.error.NotFoundException;
import nada.model.PathResult;
import nada.model.Person;
import nada.repository.GraphView;
import nada.repository.InMemoryNetworkRepository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PathService {

    private final InMemoryNetworkRepository repository;

    public PathService(InMemoryNetworkRepository repository) {
        this.repository = repository;
    }

    /**
     * Shortest path of at most {@link Limits#MAX_HOPS} edges. The same person is a zero-hop path.
     * Breadth-first search stops expanding once that hop limit is reached, so a longer chain is
     * reported as disconnected and cycles cannot be walked forever.
     */
    public PathResult shortestPath(long from, long to) {
        return repository.read(graph -> search(graph, from, to));
    }

    private PathResult search(GraphView graph, long from, long to) {
        if (!graph.contains(from)) {
            throw NotFoundException.person(from);
        }
        if (!graph.contains(to)) {
            throw NotFoundException.person(to);
        }
        if (from == to) {
            return PathResult.connected(List.of(graph.person(from)));
        }

        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Map<Long, Long> previous = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        seen.add(from);
        queue.add(new SearchNode(from, 0));

        while (!queue.isEmpty()) {
            SearchNode current = queue.removeFirst();
            if (current.depth == Limits.MAX_HOPS) {
                continue;
            }
            for (long next : graph.neighborIds(current.id)) {
                if (!seen.add(next)) {
                    continue;
                }
                previous.put(next, current.id);
                if (next == to) {
                    return PathResult.connected(reconstruct(graph, previous, from, to));
                }
                queue.addLast(new SearchNode(next, current.depth + 1));
            }
        }
        return PathResult.disconnected();
    }

    private List<Person> reconstruct(GraphView graph, Map<Long, Long> previous, long from, long to) {
        ArrayDeque<Person> path = new ArrayDeque<>();
        long cursor = to;
        path.addFirst(graph.person(cursor));
        while (cursor != from) {
            Long parent = previous.get(cursor);
            if (parent == null) {
                throw new IllegalStateException("path reconstruction missed a parent for " + cursor);
            }
            cursor = parent;
            path.addFirst(graph.person(cursor));
        }
        return new ArrayList<>(path);
    }

    private record SearchNode(long id, int depth) {
    }
}

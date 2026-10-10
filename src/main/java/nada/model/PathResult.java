package nada.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Shortest-path answer. When {@code connected} is false, hops and path are omitted from JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PathResult(boolean connected, Integer hops, List<Person> path) {

    public static PathResult connected(List<Person> path) {
        List<Person> copy = List.copyOf(path);
        return new PathResult(true, copy.size() - 1, copy);
    }

    public static PathResult disconnected() {
        return new PathResult(false, null, null);
    }
}

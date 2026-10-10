package nada.repository;

import nada.model.Person;

import java.util.List;

/**
 * Read-only view of the people graph. Valid only for the duration of
 * {@link InMemoryNetworkRepository#read(java.util.function.Function)}.
 */
public interface GraphView {

    boolean contains(long id);

    Person person(long id);

    /** Direct contacts in the order the connections were first recorded. */
    List<Long> neighborIds(long id);
}

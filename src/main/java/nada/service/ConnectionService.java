package nada.service;

import nada.error.BadRequestException;
import nada.error.NotFoundException;
import nada.model.Person;
import nada.repository.InMemoryNetworkRepository;

import java.util.List;
import java.util.OptionalLong;

public final class ConnectionService {

    private final InMemoryNetworkRepository repository;

    public ConnectionService(InMemoryNetworkRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a mutual connection. The same pair may be sent again, including reversed, without
     * changing stored contacts. A self-connection is rejected even when that id does not exist.
     */
    public void connect(long a, long b) {
        if (a == b) {
            throw new BadRequestException("a person cannot be connected to themselves");
        }
        OptionalLong missing = repository.connect(a, b);
        if (missing.isPresent()) {
            throw NotFoundException.person(missing.getAsLong());
        }
    }

    public List<Person> contacts(long id) {
        return repository.contactsOf(id).orElseThrow(() -> NotFoundException.person(id));
    }
}

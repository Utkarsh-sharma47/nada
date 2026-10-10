package nada.service;

import nada.error.BadRequestException;
import nada.error.NotFoundException;
import nada.model.Person;
import nada.repository.InMemoryNetworkRepository;

import java.util.List;

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
        if (!repository.connect(a, b)) {
            if (!repository.contains(a)) {
                throw NotFoundException.person(a);
            }
            throw NotFoundException.person(b);
        }
    }

    public List<Person> contacts(long id) {
        return repository.contactsOf(id).orElseThrow(() -> NotFoundException.person(id));
    }
}

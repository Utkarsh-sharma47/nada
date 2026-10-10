package nada.service;

import nada.error.BadRequestException;
import nada.model.Person;
import nada.repository.InMemoryNetworkRepository;

public final class PersonService {

    private final InMemoryNetworkRepository repository;

    public PersonService(InMemoryNetworkRepository repository) {
        this.repository = repository;
    }

    /**
     * Stores {@code name} exactly as supplied. Blank names, including whitespace-only names, are rejected.
     */
    public Person create(String name) {
        if (name == null) {
            throw new BadRequestException("name is required");
        }
        if (name.isBlank()) {
            throw new BadRequestException("name must not be blank");
        }
        return repository.insert(name);
    }
}

package nada.model;

/**
 * A person stored by the API. The id is assigned by the server. The name is kept exactly as supplied.
 */
public record Person(long id, String name) {
}

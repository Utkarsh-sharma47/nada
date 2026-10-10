package nada.error;

public final class NotFoundException extends ApiException {

    private NotFoundException(String message) {
        super(404, message);
    }

    public static NotFoundException person(long id) {
        return new NotFoundException("no person " + id);
    }

    public static NotFoundException route() {
        return new NotFoundException("not found");
    }
}

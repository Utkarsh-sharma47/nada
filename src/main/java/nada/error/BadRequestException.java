package nada.error;

public final class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(400, message);
    }
}

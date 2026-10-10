package nada;

/**
 * Bounds that the API enforces in one place.
 */
public final class Limits {

    /** Shortest-path answers never walk more than this many edges. */
    public static final int MAX_HOPS = 3;

    /** Reject request bodies larger than this so a bad client cannot exhaust memory. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    public static final int DEFAULT_PORT = 8080;

    private Limits() {
    }
}

package dev.mackerel.d1jdbc.transport;

/**
 * Runtime wrapper for transport-layer failures (HTTP errors, D1 error
 * envelopes, malformed responses). Converted to {@link java.sql.SQLException}
 * at the JDBC surface via {@link dev.mackerel.d1jdbc.D1Codec}.
 */
public class TransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sqlState;
    private final int vendorCode;

    public TransportException(String message) {
        this(message, null, 0, null);
    }

    public TransportException(String message, Throwable cause) {
        this(message, null, 0, cause);
    }

    public TransportException(String message, String sqlState, int vendorCode, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    public String sqlState() {
        return sqlState;
    }

    public int vendorCode() {
        return vendorCode;
    }
}

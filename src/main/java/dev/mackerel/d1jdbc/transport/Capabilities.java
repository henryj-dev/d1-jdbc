package dev.mackerel.d1jdbc.transport;

/**
 * Feature capabilities a transport advertises to the JDBC surface.
 *
 * <p>{@link dev.mackerel.d1jdbc.D1DatabaseMetaData} reflects these so callers
 * can discover, e.g., whether atomic batches are actually atomic on the chosen
 * transport (true for the self-deployed proxy Worker via {@code db.batch()},
 * best-effort only for the public REST API).
 */
public record Capabilities(
        boolean supportsAtomicBatch,
        boolean supportsSessions,
        String transportName) {
}

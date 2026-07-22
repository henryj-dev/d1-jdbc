package dev.mackerel.d1jdbc.internal;

/**
 * Confirmed Cloudflare D1 platform limits (DESIGN 9-1, sourced from
 * developers.cloudflare.com/d1/platform/limits). The driver enforces these
 * client-side so violations fail fast with a clear {@code SQLException} instead
 * of an opaque transport error.
 */
public final class D1Limits {

    /**
     * Maximum SQL statement length in UTF-8 bytes: 100,000 B (100 KB).
     * (DESIGN 9-1: "문장 길이".)
     */
    public static final int MAX_SQL_BYTES = 100_000;

    /**
     * Maximum number of bound parameters per statement: 100.
     * (DESIGN 9-1: "바인딩 파라미터 수".)
     */
    public static final int MAX_BOUND_PARAMS = 100;

    /**
     * Maximum size of a single BLOB / string / row value in bytes:
     * 2,000,000 B (2 MB). (DESIGN 9-1: "BLOB·문자열·행 크기".)
     */
    public static final int MAX_VALUE_BYTES = 2_000_000;

    /**
     * Maximum statements per atomic batch: 1,000 — the number of binding
     * queries allowed per Worker invocation on the Paid tier, which is the
     * proxy transport's batch ceiling. (DESIGN 9-1: "바인딩 호출당 쿼리 수".)
     * Larger JDBC batches are split into chunks of this size; a manual
     * transaction buffer beyond this size cannot be committed atomically.
     */
    public static final int MAX_ATOMIC_BATCH_STATEMENTS = 1_000;

    private D1Limits() {
    }
}

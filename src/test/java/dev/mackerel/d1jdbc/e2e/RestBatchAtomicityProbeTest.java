package dev.mackerel.d1jdbc.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * DESIGN section 9-2 discovery probe: does the public REST {@code {batch:[...]}}
 * form execute atomically (all-or-nothing) when one statement fails a
 * constraint? This bypasses the driver's sequential {@code RestTransport.batch()}
 * and posts the first-class batch body DIRECTLY with {@link HttpClient}.
 *
 * <p>The atomicity result is UNKNOWN and is what we are measuring, so it is only
 * logged — never hard-asserted. The single hard assertion is that the constraint
 * violation was reported. Read the printed
 * {@code REST {batch:[...]} atomicity: ...} line to decide whether
 * {@code RestTransport.supportsAtomicBatch} should become {@code true}.
 */
@Tag("e2e")
class RestBatchAtomicityProbeTest {

    private static final String TABLE = "d1_jdbc_e2e";
    private static Connection conn;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @BeforeAll
    static void openAndCreateTable() throws Exception {
        E2EConfig.assumeRest();
        Class.forName("dev.mackerel.d1jdbc.D1Driver");
        conn = DriverManager.getConnection(E2EConfig.restJdbcUrl());
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
            s.executeUpdate("CREATE TABLE " + TABLE
                    + " (id INTEGER PRIMARY KEY, name TEXT UNIQUE, flag INTEGER, payload BLOB)");
        }
    }

    @AfterAll
    static void dropTableAndClose() throws SQLException {
        if (conn != null) {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
            } finally {
                conn.close();
            }
        }
    }

    @Test
    void restBatchFormReportsConstraintViolationAndRevealsAtomicity() throws Exception {
        // Two inserts of the same name; the 2nd violates the UNIQUE(name) constraint.
        String body = "{\"batch\":["
                + "{\"sql\":\"INSERT INTO " + TABLE + "(name) VALUES(?)\",\"params\":[\"probeA\"]},"
                + "{\"sql\":\"INSERT INTO " + TABLE + "(name) VALUES(?)\",\"params\":[\"probeA\"]}"
                + "]}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(E2EConfig.restRawEndpoint()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + E2EConfig.apiToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        String responseBody = response.body() == null ? "" : response.body();
        String lower = responseBody.toLowerCase();
        boolean constraintReported = lower.contains("unique") || lower.contains("constraint")
                || lower.contains("\"success\":false") || response.statusCode() >= 400;
        assertTrue(constraintReported,
                "the UNIQUE violation must be reported by the REST batch response (HTTP "
                        + response.statusCode() + "): " + snippet(responseBody));

        int count = countProbeRows();
        String verdict = (count == 0)
                ? "ATOMIC (rolled back)"
                : "NON-ATOMIC (partial applied, " + count + " row(s) present)";
        System.out.println();
        System.out.println("========================================================");
        System.out.println("REST {batch:[...]} atomicity: " + verdict);
        System.out.println("  -> set RestTransport.supportsAtomicBatch = "
                + (count == 0) + " (DESIGN 9-2)");
        System.out.println("========================================================");
        System.out.println();

        // Clean up any probe rows that were applied.
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("DELETE FROM " + TABLE + " WHERE name = 'probeA'");
        }
    }

    private static int countProbeRows() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM " + TABLE + " WHERE name = 'probeA'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String snippet(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}

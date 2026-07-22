package dev.mackerel.d1jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/** Driver registration via ServiceLoader / DriverManager (JDBC 4.x). */
class D1DriverRegistrationTest {

    private static final String SAMPLE_URL =
            "jdbc:cloudflare-d1:rest://acct/db?token=x";

    @Test
    void driverManagerResolvesDriverForSampleUrl() throws SQLException {
        Driver d = DriverManager.getDriver(SAMPLE_URL);
        assertNotNull(d, "DriverManager located a driver for the D1 URL");
        assertInstanceOf(D1Driver.class, d, "resolved to the D1 driver via ServiceLoader");
    }

    @Test
    void driverAcceptsOwnUrlAndRejectsForeign() throws SQLException {
        Driver d = DriverManager.getDriver(SAMPLE_URL);
        assertTrue(d.acceptsURL(SAMPLE_URL));
        assertFalse(d.acceptsURL("jdbc:postgresql://host/db"));
    }

    @Test
    void driverIsNotJdbcCompliant() throws SQLException {
        Driver d = DriverManager.getDriver(SAMPLE_URL);
        assertFalse(d.jdbcCompliant(), "D1 is not a fully JDBC-compliant SQL engine");
    }

    @Test
    void connectReturnsNullForForeignUrl() throws SQLException {
        // Per the JDBC contract connect() returns null (not an exception) for a URL
        // this driver does not own, so DriverManager can try the next driver.
        assertNull(new D1Driver().connect("jdbc:postgresql://host/db", null));
    }
}

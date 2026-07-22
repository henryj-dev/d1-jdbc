package dev.mackerel.d1jdbc.transport;

import java.util.List;

/**
 * A single D1 statement to execute: a SQL template with positional {@code ?}
 * parameters and the ordered list of bound parameter values.
 *
 * <p>Parameter values are already serialized to the D1-allowed set
 * (Long/Double/String/null/byte[]) by the JDBC surface before reaching here.
 */
public record D1Request(String sql, List<Object> params) {
}

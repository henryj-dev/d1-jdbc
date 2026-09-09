# d1-jdbc

[![CI](https://github.com/mack-erel/d1-jdbc/actions/workflows/ci.yml/badge.svg)](https://github.com/mack-erel/d1-jdbc/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/mack-erel/d1-jdbc.svg)](https://jitpack.io/#mack-erel/d1-jdbc)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

A JDBC driver for [Cloudflare D1](https://developers.cloudflare.com/d1/) (edge SQLite).
**Zero runtime dependencies** — only the JDK (`java.net.http` + a built-in JSON codec).

한국어 문서: [README.ko.md](README.ko.md)

D1 speaks a simple `{sql, params}` HTTP contract everywhere; this driver reproduces
that contract beneath a standard JDBC surface, with two interchangeable transports:

| Transport | URL scheme | Deploy needed | Atomic batch | Sessions (read-your-write) |
|---|---|---|---|---|
| **REST** — public D1 REST API | `jdbc:cloudflare-d1:rest://…` | none | ✅ (`{batch:[…]}`, verified live) | ❌ (not exposed over REST) |
| **Proxy** — self-deployed Worker | `jdbc:cloudflare-d1:proxy://…` | one Worker ([`proxy/`](proxy/)) | ✅ (`db.batch()`) | ✅ (`x-d1-bookmark`) |

Works in GUI tools: browsing a live D1 database from **DBeaver** (tables, columns,
primary keys, data) is verified — see [DBeaver setup](#dbeaver--gui-tools).

## Installation

Via [JitPack](https://jitpack.io/#mack-erel/d1-jdbc):

```kotlin
// build.gradle.kts
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.mack-erel:d1-jdbc:v0.1.1")
}
```

```xml
<!-- pom.xml -->
<repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>
<dependency>
  <groupId>com.github.mack-erel</groupId>
  <artifactId>d1-jdbc</artifactId>
  <version>v0.1.1</version>
</dependency>
```

## Quick start

```java
// REST transport: no deployment, just an API token with Account · D1 · Edit scope.
String url = "jdbc:cloudflare-d1:rest://<account_id>/<database_id>?token=<API_TOKEN>";

try (Connection conn = DriverManager.getConnection(url)) {
    try (PreparedStatement ps =
            conn.prepareStatement("INSERT INTO users(name, active) VALUES (?, ?)")) {
        ps.setString(1, "alice");
        ps.setBoolean(2, true);      // stored as INTEGER 1/0 (D1 convention)
        ps.executeUpdate();
    }
    try (Statement s = conn.createStatement();
         ResultSet rs = s.executeQuery("SELECT id, name FROM users")) {
        while (rs.next()) {
            System.out.println(rs.getLong("id") + " " + rs.getString("name"));
        }
    }
}
```

The driver self-registers via `ServiceLoader` — no `Class.forName` needed.

### JDBC URLs

```
jdbc:cloudflare-d1:rest://<account_id>/<database_id>?token=<API_TOKEN>
jdbc:cloudflare-d1:proxy://<worker-host>[/<base-path>]?token=<SHARED_SECRET>
```

Optional properties (query string or `Properties`): `connectTimeoutMillis`
(default 10000), `requestTimeoutMillis` (default 30000), `apiBase` (REST),
`scheme` (proxy, default `https`). Username/password are unused — the token in
the URL is the credential.

### Transactions & batches

D1 has **no interactive transactions** (each HTTP request auto-commits).
The driver maps JDBC onto what D1 actually guarantees:

- `setAutoCommit(false)` buffers writes client-side; `commit()` sends them as
  **one atomic batch** (all-or-nothing on both transports); `rollback()` discards
  the buffer. Reads inside a manual transaction do not see buffered writes.
- `executeBatch()` chunks at 1,000 statements (a D1 ceiling); each chunk is
  atomic, the whole batch is not.
- A manual transaction larger than 1,000 statements is rejected rather than
  silently split.

### Limits (enforced client-side, fail fast)

| Limit | Value |
|---|---|
| SQL statement length | 100,000 bytes |
| Bound parameters / statement | 100 |
| String/BLOB value size | 2,000,000 bytes |
| Statements / atomic batch | 1,000 |

## DBeaver / GUI tools

`DatabaseMetaData` catalog introspection (`getTables`, `getColumns`,
`getPrimaryKeys`, `getIndexInfo`, `getImportedKeys`, …) is implemented, so
generic-JDBC tools can browse D1 schemas.

1. **Database → Driver Manager → New**: set *Class Name*
   `dev.mackerel.d1jdbc.D1Driver`, add the driver jar under *Libraries*.
2. *URL template*: `jdbc:cloudflare-d1:rest://{account_id}/{database_id}?token={token}`.
3. New connection → paste your full JDBC URL. Leave **username/password empty**
   (tick *No authentication* to stop prompts).

## Design & internals

- [`docs/DESIGN.md`](docs/DESIGN.md) — wire contract analysis (from
  `workers-sdk`/`workerd` sources), JDBC mapping spec, confirmed limits and
  corrections from the official docs (§9), all verified live against real D1.
- [`proxy/`](proxy/) — the transport-B Worker (deploy once, reuse from any JVM).
- Type mapping: SQLite dynamic typing → JDBC via declared-type affinity;
  `boolean → 1/0`, `byte[] ↔ JSON number array` (matches D1's own BLOB
  representation), dates/times as ISO-8601 TEXT.

## Testing

- **Offline**: 113 unit tests against a mock transport — `./gradlew test` or the
  javac + JUnit-console path used in [CI](.github/workflows/ci.yml).
- **Live e2e (opt-in)**: 15 tests against a real D1 database, auto-skipped
  unless credentials are provided — see [`docs/E2E.md`](docs/E2E.md).

## Status & roadmap

Working driver, live-verified on both transports. Not yet battle-tested in
production. Notable gaps: no streaming cursors (results are fully
materialized; page with `LIMIT/OFFSET`), duplicate identically-named result
columns collapse on the proxy transport (REST is fully faithful), no
interactive transactions (a D1 platform constraint, not a driver gap).

## License

[Apache-2.0](LICENSE)

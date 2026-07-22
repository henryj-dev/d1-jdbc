# d1-jdbc-proxy

Self-deployed Cloudflare Worker that fronts a D1 database for the **proxy
transport** (transport B) of the d1-jdbc driver. Deploy it once; the JVM
application reuses the single permanent Worker across all connections.

Why a permanent Worker: the driver runs outside the edge and cannot access a D1
binding directly. The public REST API (transport A) covers autocommit queries
but cannot run truly atomic parameterized batches. This Worker forwards to its
native `DB` binding, so `db.batch([...])` gives real atomicity and native
sessions.

## Endpoints

| Method + path | Request body | Response |
|---|---|---|
| `POST /query` | `{ "sql": "...", "params": [...] }` | `{ columns, rows, meta }` |
| `POST /batch` | `[{ "sql": "...", "params": [...] }, ...]` | `[{ columns, rows, meta }, ...]` (atomic) |

Responses are normalized to the `ROWS_AND_COLUMNS` shape (no Cloudflare
envelope) so the driver's shared `D1Codec` parses them directly. The session
bookmark header `x-cf-d1-session-commit-token` is threaded through in both
directions for read-your-write consistency.

**Read vs write handling.** `/query` branches on statement kind:

- **Reads** (`SELECT`/`WITH`/`PRAGMA`/`EXPLAIN`/`… RETURNING`) use
  `stmt.raw({ columns: true })` — true ROWS_AND_COLUMNS. Column order and
  duplicate/aliased names survive, and the column header is returned even for a
  zero-row result. `.raw()` omits `meta`, which reads do not need.
- **Writes** use `stmt.all()` so `meta` (`changes` / `last_row_id`) survives for
  `getUpdateCount()` / `getGeneratedKeys()`. Writes have no result columns.

`/batch` runs `db.batch([...])` atomically (the driver's `commit()` buffers
writes into one batch) and keeps `meta` per statement.

## Deploy

1. Set your database id in `wrangler.jsonc` (`d1_databases[0].database_id`):

   ```sh
   wrangler d1 list          # find the uuid
   ```

2. Set the shared secret (never commit it):

   ```sh
   wrangler secret put PROXY_SECRET
   ```

3. Deploy:

   ```sh
   wrangler deploy
   ```

The Worker fails closed (HTTP 503) if `PROXY_SECRET` is not configured.

## Authentication

The driver sends `Authorization: Bearer <SHARED_SECRET>`; the Worker compares it
against `PROXY_SECRET`. For stronger auth, front the Worker with Cloudflare
Access and replace `checkAuth()` in `src/index.ts`.

## Connecting from JDBC

```
jdbc:cloudflare-d1:proxy://<your-worker-host>/<optional-base-path>?token=<SHARED_SECRET>
```

Example:

```
jdbc:cloudflare-d1:proxy://d1-jdbc-proxy.example.workers.dev?token=SHARED_SECRET
```

The `token` URL property is sent as the bearer secret. Optional properties:
`connectTimeoutMillis` (default 10000), `requestTimeoutMillis` (default 30000),
`scheme` (default `https`).

## Caveat: duplicate column names in batched reads

The read path (`/query`) uses `.raw({ columns: true })` and is faithful,
including zero-row column headers and duplicate/aliased column names. Reads
routed through **`/batch`** (rare — batch is the atomic-write path) fall back to
`.all()`'s array-of-objects, where two identically-named result columns collapse
into one. Run reads via `/query` (the driver does this for `executeQuery`) to
avoid it.

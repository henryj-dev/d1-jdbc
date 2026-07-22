/**
 * D1 JDBC proxy Worker (transport B).
 *
 * Endpoints:
 *   POST /query  body {sql, params?}            -> { columns, rows, meta }
 *   POST /batch  body [{sql, params?}, ...]     -> [ { columns, rows, meta }, ... ]  (atomic)
 *
 * The response shape is normalized to ROWS_AND_COLUMNS so the shared D1Codec on
 * the JVM side can parse it directly (no Cloudflare envelope). The session
 * bookmark header (x-d1-bookmark) is threaded through in both
 * directions to preserve read-your-write consistency.
 */

const BOOKMARK_HEADER = "x-d1-bookmark";

export interface Env {
  DB: D1Database;
  /** Shared secret; set via `wrangler secret put PROXY_SECRET`. */
  PROXY_SECRET?: string;
}

interface StatementSpec {
  sql: string;
  params?: unknown[];
}

interface NormalizedResult {
  columns: string[];
  rows: unknown[][];
  meta: unknown;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    // 1) Shared-secret auth check (stub — swap for Cloudflare Access if preferred).
    const authError = checkAuth(req, env);
    if (authError) {
      return authError;
    }

    if (req.method !== "POST") {
      return new Response("method not allowed", { status: 405 });
    }

    const url = new URL(req.url);
    const inboundBookmark = req.headers.get(BOOKMARK_HEADER);
    // Continue the caller's session if a bookmark was sent, else start a fresh
    // primary-constrained session so we still return a bookmark to thread forward.
    const session = env.DB.withSession(inboundBookmark ?? "first-primary");

    try {
      if (url.pathname === "/query") {
        const body = (await req.json()) as StatementSpec;
        if (!body || typeof body.sql !== "string") {
          return json(
            { error: "invalid body: expected { sql, params? }" },
            session.getBookmark(),
            400,
          );
        }
        return json(await runOne(session, body), session.getBookmark());
      }

      if (url.pathname === "/batch") {
        const specs = (await req.json()) as StatementSpec[];
        if (!Array.isArray(specs)) {
          return json(
            { error: "invalid body: expected an array of { sql, params? }" },
            session.getBookmark(),
            400,
          );
        }
        const stmts = specs.map((s) =>
          session.prepare(s.sql).bind(...(s.params ?? [])),
        );
        const results = await session.batch(stmts); // atomic
        // Batches are the atomic-write path (Connection.commit buffers writes),
        // so meta matters; normalizeAll keeps it.
        return json(results.map(normalizeAll), session.getBookmark());
      }

      return new Response("not found", { status: 404 });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      return json({ error: message }, session.getBookmark(), 400);
    }
  },
};

function checkAuth(req: Request, env: Env): Response | null {
  if (!env.PROXY_SECRET) {
    // No secret configured: fail closed rather than serve unauthenticated D1.
    return new Response("proxy not configured (PROXY_SECRET missing)", {
      status: 503,
    });
  }
  const header = req.headers.get("authorization") ?? "";
  const expected = `Bearer ${env.PROXY_SECRET}`;
  if (header !== expected) {
    return new Response("unauthorized", { status: 401 });
  }
  return null;
}

/**
 * Run a single /query statement via `.all()`, which returns BOTH `meta`
 * (changes / last_row_id — needed for writes) AND the result rows as objects
 * (needed for reads). {@link normalizeAll} reshapes to {columns, rows, meta}.
 *
 * We deliberately do NOT use `.raw({ columns: true })`: on the Sessions API path
 * (`env.DB.withSession().prepare()`) the deployed D1 runtime does not honor the
 * `columns` option — it returns data rows with NO column-name header — so column
 * names must come from the object keys of `.all()` (verified live, DESIGN 9-2).
 * Tradeoff: two identically-named result columns collapse (rare). The REST
 * transport (`/raw`) has full ROWS_AND_COLUMNS fidelity if you need it.
 */
async function runOne(
  session: D1DatabaseSession,
  spec: StatementSpec,
): Promise<NormalizedResult> {
  const stmt = session.prepare(spec.sql).bind(...(spec.params ?? []));
  return normalizeAll(await stmt.all());
}

/**
 * Reshape a D1Result ({@code {results: object[], meta}}) into
 * {@code {columns, rows, meta}}, keeping `meta`. Columns are the union of keys
 * across all rows. Residual limitation: duplicate identically-named columns
 * collapse (array-of-objects cannot represent them) — the read path above avoids
 * this via `.raw({ columns: true })`; this shape is used for writes and batches
 * where result columns are absent or secondary.
 */
function normalizeAll(result: D1Result): NormalizedResult {
  const objects = (result.results ?? []) as Record<string, unknown>[];
  const columns: string[] = [];
  for (const obj of objects) {
    for (const k of Object.keys(obj)) {
      if (!columns.includes(k)) {
        columns.push(k);
      }
    }
  }
  const rows = objects.map((obj) => columns.map((c) => obj[c]));
  return { columns, rows, meta: result.meta };
}

function json(
  payload: unknown,
  bookmark: string | null,
  status = 200,
): Response {
  const headers: Record<string, string> = {
    "content-type": "application/json",
  };
  if (bookmark) {
    headers[BOOKMARK_HEADER] = bookmark;
  }
  return new Response(JSON.stringify(payload), { status, headers });
}

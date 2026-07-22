# Running E2E tests (opt-in, live D1)

The end-to-end suite runs the driver against a **real** Cloudflare D1 database
over the network. It is **opt-in**: with no credentials present, every E2E test
is reported **SKIPPED** (never failed), so the offline unit suite is unaffected.
Supply credentials only when you want to exercise the live path.

All E2E tests are tagged `@Tag("e2e")` and live under
`src/test/java/dev/mackerel/d1jdbc/e2e/`. They use a dedicated table
`d1_jdbc_e2e` created with `DROP TABLE IF EXISTS` in `@BeforeAll`/`@AfterAll`, so
your existing data is never touched.

## Configuration

Credentials resolve in order: **(1) environment variables**, then **(2) a
gitignored `e2e.local.properties`** at the repository root. Environment variables
win. Keys:

| Key | Transport | Purpose |
|---|---|---|
| `D1_ACCOUNT_ID`  | REST  | Cloudflare account id |
| `D1_DATABASE_ID` | REST  | D1 database id (uuid) |
| `D1_API_TOKEN`   | REST  | API token, scope `Account · D1 · Edit` |
| `D1_PROXY_URL`   | Proxy | Deployed proxy Worker base URL |
| `D1_PROXY_SECRET`| Proxy | Shared secret the Worker checks |

REST tests run when `D1_ACCOUNT_ID` + `D1_DATABASE_ID` + `D1_API_TOKEN` are all
set; proxy tests run when `D1_PROXY_URL` + `D1_PROXY_SECRET` are both set. Either
group can run independently — missing the other just skips that group.

The driver JDBC URLs are built from the config:

```
jdbc:cloudflare-d1:rest://<account_id>/<database_id>?token=<api_token>
jdbc:cloudflare-d1:proxy://<worker-host>/<path>?token=<proxy_secret>
```

### Option A — environment variables

```sh
export D1_ACCOUNT_ID=...           # wrangler whoami
export D1_DATABASE_ID=...          # wrangler d1 create prints this
export D1_API_TOKEN=...            # least-privilege; roll after use
export D1_PROXY_URL=https://d1-jdbc-proxy.<subdomain>.workers.dev
export D1_PROXY_SECRET=...
```

### Option B — local properties file

```sh
cp e2e.local.properties.example e2e.local.properties
# then edit e2e.local.properties (it is gitignored)
```

## Getting the credentials

**Account id** — `wrangler whoami`, or the Cloudflare dashboard sidebar.

**Create a D1 database:**

```sh
wrangler d1 create d1-jdbc-e2e
# copy the printed database_id into D1_DATABASE_ID
```

**API token (minimal scope)** — create at
<https://dash.cloudflare.com/profile/api-tokens> → *Create Token* → *Custom
token*:

- Permissions: **Account · D1 · Edit** (this scope alone is sufficient).
- Nothing else is required. Prefer the least privilege possible.
- **Roll (revoke) the token after your test run.**

**Deploy the proxy Worker** — see [`proxy/README.md`](../proxy/README.md). Set
`D1_PROXY_URL` to the deployed `*.workers.dev` URL and `D1_PROXY_SECRET` to the
shared secret the Worker verifies.

## Running

Compile and run with the JUnit console launcher (Gradle is not required):

```sh
rm -rf build/classes build/test-classes && mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' > build/main-sources.txt
javac --release 17 -d build/classes @build/main-sources.txt
find src/test/java -name '*.java' > build/test-sources.txt
javac --release 17 -cp build/classes:build/testlib/junit-console.jar -d build/test-classes @build/test-sources.txt
java -jar build/testlib/junit-console.jar execute \
  --class-path build/classes:build/test-classes:src/main/resources \
  --scan-classpath --details=tree
```

- **Without credentials:** the e2e tests report as skipped; offline tests pass.
- **With credentials:** the e2e tests connect to live D1 and run.

To run *only* the e2e tests, add `--include-tag=e2e`; to exclude them, add
`--exclude-tag=e2e`.

## The REST batch atomicity probe

`RestBatchAtomicityProbe` (DESIGN §9-2) is a discovery test. It posts the public
REST `{"batch":[...]}` first-class form **directly** (bypassing the driver's
sequential `RestTransport.batch()`) with a second statement that violates a
`UNIQUE` constraint, then counts the surviving rows. It **asserts only** that the
constraint violation was reported, and **logs** the discovered behavior:

```
REST {batch:[...]} atomicity: ATOMIC (rolled back)      # -> supportsAtomicBatch = true
REST {batch:[...]} atomicity: NON-ATOMIC (partial ...)  # -> supportsAtomicBatch = false
```

Use that printed verdict to set `RestTransport.supportsAtomicBatch` afterwards.

## Security

- The token needs only `Account · D1 · Edit`. Do not grant more.
- `e2e.local.properties` is gitignored; never commit real credentials.
- Roll/revoke the API token after use.

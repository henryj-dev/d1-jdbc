# Cloudflare D1 JDBC 드라이버 — 설계 문서

> 상태: 스켈레톤 구현 완료, 문서 리서치 반영 (v0.2)
> 근거: `cloudflare/workers-sdk`·`cloudflare/workerd` 소스 분석 + **Cloudflare 공식 문서 리서치**(2026-07, §9). 소스 분석과 문서가 충돌하면 §9가 우선.

## 0. 목표와 배경

Cloudflare D1(엣지 SQLite)을 **JVM 애플리케이션에서 표준 JDBC로** 접근할 수 있게 하는 드라이버를 만든다.
출발점은 workers-sdk가 `remote: true` 바인딩을 처리하는 방식("remote 처리")에 대한 분석이며,
그 처리의 본질인 `{sql, params}` HTTP 계약을 JDBC 표면 아래에 그대로 재현한다.

핵심 제약: **JDBC 드라이버는 엣지 밖 JVM에서 돌기 때문에 D1 바인딩에 직접 접근할 수 없다.**
따라서 실제 도달 경로(전송로)를 명시적으로 골라야 한다. 본 프로젝트는 **두 전송로를 Transport 추상화로 모두 지원**한다.

---

## 1. 불변 사실: D1는 하나의 wire 계약을 어디서나 반복한다

D1의 로컬 시뮬 · 바인딩 클라이언트 · 엣지 프록시 · 공개 REST API가 **동일한 요청/응답 계약**을 쓴다.

```
POST <base>/query?resultsFormat=<FMT>      # 읽기/일반
POST <base>/execute?resultsFormat=NONE     # 쓰기
Content-Type: application/json
x-d1-bookmark: <bookmark>                  # 세션 일관성(바인딩=B 전용), 요청·응답 양방향  ※§9-3

Body 단일:  { "sql": "... ? ...", "params": [v1, v2, ...] }
Body 배치:  [ {sql, params}, {sql, params}, ... ]    # 원자적 실행
```

| 위치 | `<base>` | 근거 |
|---|---|---|
| workerd 바인딩 클라이언트 | `http://d1` (합성 호스트) | `workerd src/cloudflare/internal/d1-api.ts:333` |
| miniflare 로컬 시뮬 (서버) | Durable Object 워커 | `workers-sdk packages/miniflare/src/workers/d1/database.worker.ts:206` |
| 엣지 프록시 (remote) | ProxyServerWorker → `env.DB.fetch()` | `workers-sdk packages/remote-bindings/templates/remoteBindings/ProxyServerWorker.ts:319` |
| 공개 REST API | `/accounts/{acct}/d1/database/{uuid}` | `workers-sdk packages/wrangler/src/d1/execute.ts:627` |

**결론:** "D1 remote 처리"란 이 `{sql, params}` 계약을 엣지 프록시 Worker 한 번 경유시키는 것뿐이다.
JDBC 드라이버는 이 계약을 구현하고, 전송로만 고르면 된다.

### 1-1. workers-sdk의 remote 처리 흐름 (참고)

```
env.DB.prepare(sql).bind(...).all()
  -> d1-api._send("/query", sql, params, "ROWS_AND_COLUMNS")
  -> this.fetcher.fetch("http://d1/query?resultsFormat=ROWS_AND_COLUMNS", {POST, body})
     (fetcher = remote 시 공유 서비스 d1:db:remote = remoteProxyClientWorker())
  -> remote-proxy-client: makeFetch()  [MF-URL, MF-Binding=DB, MF-Header-* 접두어]
  -> ProxyServerWorker: 일반 fetch 경로 -> env.DB.fetch("http://d1/query?...", 복원요청)
  -> 실제 D1
```

- D1 remote는 **요청/응답 완결형 HTTP 프록시**다 (VPC의 raw-TCP 릴레이와 대비). 소켓 유지 상태가 없어 **완전 stateless HTTP**로 재현 가능.
- **SDK는 이 ProxyServerWorker를 자동으로/임시로 배포한다.** `startRemoteProxySession()`(`packages/remote-bindings/src/start-remote-proxy-session.ts:92`)이 dev 세션 시작 시 계정에 preview 업로드하고, 세션 종료 시 `dispose()`한다. **영구 배포물이 아니다.**

---

## 2. 전송로 결정: 둘 다 (Transport 추상화)

| | A) 공개 REST API | B) 셀프 프록시 Worker (studied 방식) |
|---|---|---|
| `<base>` | `api.cloudflare.com/client/v4/accounts/{acct}/d1/database/{uuid}` | `https://<your>.workers.dev` |
| 인증 | `Authorization: Bearer <API token>` | 직접 설계 (공유 시크릿 / Access) |
| 배포 필요 | 없음 | Worker 1개 **영구 배포** |
| 응답 봉투 | `{ result, success, errors[], messages[] }` (CF 표준) | 직접 정의 (봉투 없이 D1Result 그대로 통과 가능) |
| 원자적 파라미터 배치 | `{batch:[{sql,params}…]}` 1급 폼 존재 — 원자성 실측 필요(§9-2) | **`db.batch([{sql,params}...])` 위임 → 원자적** |
| 세션(bookmark) | **미지원** (Sessions는 바인딩 전용, REST 미제공 §9-3) | binding 네이티브 |
| Rate limit | REST API 한도 | Worker 한도(여유) |

- SDK가 자동으로 하는 임시 배포를, B안은 **영구 배포 워커 1개로 고정**한 것. JVM 앱은 커넥션마다 워커를 올릴 수 없으므로 영구 배포가 현실적.
- A안은 무배포 baseline. autocommit 단순 질의에 최적.

방침: **REST를 baseline으로, 원자적 트랜잭션 등 고급 기능은 Proxy로.** 두 전송로의 능력 차이는 `Capabilities`로 신고하고 `DatabaseMetaData`가 이를 반영한다.

---

## 3. 아키텍처

```
+-------------------------------------------------+
| JDBC 표면 (Driver/Connection/PreparedStmt/RS)    |
+-------------------------------------------------+
| 프로토콜 코덱 (전송로 무관, 공유)                 |
|  - 요청:  {sql, params} + resultsFormat          |
|  - 파라미터 직렬화 (bool->1/0, blob->int[], ...)  |
|  - 응답 파싱: {columns, rows} + meta             |
|  - 에러 -> SQLException                          |
+-------------------+-----------------------------+
|  D1Transport 인터페이스                          |
|   query(sql, params, fmt, bookmark)             |
|   batch(stmts, bookmark)                         |
|   capabilities()                                 |
+-------------------+-----------------------------+
| RestTransport (A)  | ProxyTransport (B)          |
| CF 봉투 언랩       | 직접 정의 계약, db.batch()  |
+--------------------+-----------------------------+
```

### 3-1. 클래스 맵

```
D1Driver             implements java.sql.Driver      - URL 파싱, Transport 선택
D1Connection                                          - base + 인증 + 현재 bookmark + autoCommit 버퍼
D1PreparedStatement                                   - SQL 템플릿 + params 배열, setXxx -> 직렬화
D1Statement                                           - 즉석 SQL
D1ResultSet                                           - ROWS_AND_COLUMNS materialize, getXxx, wasNull
D1ResultSetMetaData                                   - columns 기반
D1DatabaseMetaData                                    - 능력 신고 (전송로별)
D1Transport (interface)
  +- RestTransport
  +- ProxyTransport
D1Codec                                               - 요청/응답 직렬화 (공유)
```

### 3-2. JDBC URL 스킴 (제안)

```
jdbc:cloudflare-d1:rest://<account_id>/<database_id>?token=<API_TOKEN>
jdbc:cloudflare-d1:proxy://<worker-host>/<path>?token=<SHARED_SECRET>
```

---

## 4. JDBC 매핑 규격 (소스에서 확정)

### 4-1. Statement 메서드 -> 엔드포인트/포맷 (`d1-api.ts`)

| D1 메서드 | endpoint | resultsFormat | JDBC 대응 |
|---|---|---|---|
| `.all()` / `.first()` / `.raw()` | `/query` | **`ROWS_AND_COLUMNS`** | `executeQuery()` -> ResultSet |
| `.run()` | `/execute` | `NONE` | `executeUpdate()` |
| `.batch([...])` | 배열 body | (요소별) | `executeBatch()` (원자적) |

→ **드라이버는 항상 `ROWS_AND_COLUMNS`(`{columns:[], rows:[[]]}`)를 쓴다.** array-of-objects는 컬럼 순서·동명 컬럼이 뭉개져 `ResultSetMetaData`를 구성할 수 없다. (REST API는 `/raw` 엔드포인트가 columns+rows를 준다.)

### 4-2. 파라미터 직렬화 — `setXxx()` (`d1-api.ts` `bind()` L485 부근)

D1가 허용하는 값 타입: **`number | string | null | 바이트배열(number[])`뿐.** 나머지는 `D1_TYPE_ERROR`.

| JDBC set | -> D1 params 값 |
|---|---|
| `setInt/Long/Short/Byte` | number (정수) |
| `setDouble/Float` | number |
| `setBigDecimal` | string 권장 (정밀도 보존) 또는 double |
| `setString` | string |
| `setBoolean` | **1 / 0** (D1엔 boolean 없음) |
| `setNull` | `null` |
| `setBytes` / `setBlob` | **`number[]` (0-255 바이트 배열)** |
| `setDate/Time/Timestamp` | ISO-8601 string 또는 epoch integer (**컨벤션 고정 필요**) |
| `setObject` | 위 규칙, 아니면 `SQLException` |

- 파라미터는 **위치 기반 `?`(순서 배열)만** 지원. 이름 파라미터 없음. JDBC `?`와 1:1.

### 4-3. 결과 역직렬화 — `getXxx()` (ROWS_AND_COLUMNS)

`results = { columns: string[], rows: (number|string|null|number[])[][] }`. SQLite 동적 타입 -> 셀 런타임 타입으로 판별.

| D1 셀 | Java 기본 | 비고 |
|---|---|---|
| number(정수) | Long/Integer | `getInt/getLong` 캐스팅 |
| number(실수) | Double | REAL |
| string | String | TEXT |
| `number[]` | `byte[]` | **BLOB** 역변환 |
| null | null | `wasNull()` 세팅 |

- `columns` -> `ResultSetMetaData`(라벨/개수). 선언 타입이 없어 타입은 값 기반 추정.
- 결과는 **한 응답에 전부 materialize** — 스트리밍 커서 없음. `setFetchSize` no-op, 큰 결과는 SQL `LIMIT/OFFSET`으로 페이징.

### 4-4. meta -> JDBC

`meta: { changes, last_row_id, rows_read, rows_written, duration, size_after, changed_db, served_by_colo, served_by_primary, served_by_region, timings: { sql_duration_ms } }`  ※§9-2 (구 `served_by` 단일 필드는 폐기)

| meta | JDBC |
|---|---|
| `changes` | `executeUpdate()` 반환 / `getUpdateCount()` |
| `last_row_id` | `getGeneratedKeys()` (INSERT rowid) |
| `rows_read/written`, `duration` | 진단/로그 (벤더 확장) |

### 4-5. 에러 -> SQLException

- 바인딩 클라이언트: 실패 응답 `{ success:false, error }` -> `throw D1_ERROR: <msg>` (`d1-api.ts:290`).
- REST: `{ success:false, errors:[{code,message}] }`.
- 드라이버는 `SQLException(message, sqlState, vendorCode)`로 감싸고 제약위반/문법오류를 SQLite 계열 SQLState로 매핑.

---

## 5. 반드시 흡수해야 할 D1의 근본 제약

1. **인터랙티브 트랜잭션 없음.** HTTP 왕복을 가로지르는 `BEGIN...COMMIT` 불가. 원자성은 **단일 요청(배치 배열/다중문) 내부**로 한정.
   - `setAutoCommit(false)` -> 문장을 클라이언트 버퍼에 모았다가 `commit()` 시 하나의 원자적 배치로 전송. `rollback()` = 버퍼 폐기.
   - 한계: 커밋 전 중간 SELECT를 같은 트랜잭션에서 다시 읽는 패턴은 불가(쓰기 중심 트랜잭션만 안전). **문서화 필수.**
2. **서버측 PreparedStatement 없음.** 매 호출 SQL 텍스트 전량 전송. PreparedStatement는 클라이언트 템플릿(파라미터 바인딩)일 뿐.
3. **세션 bookmark = read-your-write 일관성이지 격리(isolation)가 아님.** `Connection`이 마지막 `x-d1-bookmark`(§9-3, 구 `x-cf-d1-session-commit-token`)를 들고 다니며 다음 요청 헤더에 실으면 읽기 복제본에서도 자기 쓰기를 본다. **단, 세션은 바인딩(B) 전용 — REST(A)는 미제공.**
4. **크기 한도.** 응답/문장/배치 문장 수 상한 존재 -> `executeBatch` 청크 분할, 큰 결과 페이징 필요.

### 트랜잭션 전략 (전송로별)

| | autocommit=true | autocommit=false (`commit()`) |
|---|---|---|
| REST (A) | 요청 1개 = 문장 1개 | 버퍼 -> `{batch:[…]}` 1요청. `supportsAtomicBatch`는 **프로브 후 확정**(현재 `false` 잠정, §9-2) |
| Proxy (B) | 요청 1개 | 버퍼 -> `/batch` = **원자적** `supportsAtomicBatch=true` |

---

## 6. 프록시 Worker 스펙 (B의 배포물)

```jsonc
// wrangler.jsonc
{
  "name": "d1-jdbc-proxy",
  "main": "src/index.ts",
  "compatibility_date": "2025-04-28",
  "d1_databases": [{ "binding": "DB", "database_id": "<uuid>" }]
}
```

```ts
// src/index.ts (핵심만)
export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    // 1) 공유 시크릿 / Access 인증 검사 (생략)
    const url = new URL(req.url);
    if (url.pathname === "/query") {
      const { sql, params, resultsFormat } = await req.json();
      const r = await env.DB.prepare(sql).bind(...(params ?? [])).all(); // 또는 raw
      return Response.json(r);
    }
    if (url.pathname === "/batch") {
      const stmts = await req.json(); // [{sql, params}, ...]
      const r = await env.DB.batch(
        stmts.map((s) => env.DB.prepare(s.sql).bind(...(s.params ?? [])))
      );
      return Response.json(r); // db.batch = 원자적
    }
    return new Response("not found", { status: 404 });
  },
};
```

- 한 번 배포해두면 JVM이 재사용. 세션 bookmark 헤더를 그대로 통과시키면 read-your-write 유지.

---

## 7. 열린 항목 — 상태 (문서 리서치 2026-07 반영)

- [x] **REST `/raw` 스키마/봉투 확정.** 봉투 `{ result:[…], success, errors:[], messages:[] }`,
      요소별 `{ success, results:{columns, rows}, meta }`. `/query`는 `results`가 행-객체 배열(컬럼 손실) →
      드라이버는 `/raw` 사용 확정. (§9-2)
- [~] **REST 원자적 배치 — 재평가 필요.** REST는 `{ batch:[{sql,params}, …] }` 1급 폼을 제공하며 "as a batch"로 실행.
      원자성(all-or-nothing) 문구가 바인딩 문서만큼 명시적이지 않음 → **라이브 프로브로 확정 후 `supportsAtomicBatch` 결정**.
      (기존 가정 `false`는 보류) (§9-2)
- [x] **Date/Time 컨벤션 = 드라이버 소유 결정.** Cloudflare는 날짜 컨벤션을 문서화하지 않음(SQLite에 DATE 타입 없음).
      드라이버 기본값: 날짜/시간을 **ISO-8601 TEXT**로 직렬화(이식성), BLOB은 **number 배열**(D1의 BLOB 읽기 표현과 일치). (§9-4)
- [~] **능력 플래그 표 — 세션 정정 반영 필요.** 세션/bookmark는 **바인딩(B) 전용, REST(A) 미제공**.
      `DatabaseMetaData`는 REST에서 세션 일관성 미지원으로 신고. (§9-3)
- [x] **크기 상한 확정 + 미문서 항목 명시.** 아래 §9-1 표. 문장 100KB / 파라미터 100개 / BLOB·문자열 2MB /
      컬럼 100개 / 바인딩 호출당 쿼리 1000개 / 쿼리 30초. **REST 요청·응답 바이트 상한, batch 문장 수 상한은 미문서.**
- [x] 빌드/타깃 확정: **Gradle(Kotlin DSL) + Java 17 + JDBC 4.2**, 런타임 의존성 0 (구현 완료).

### 구현에 반영할 정정 (문서와 모순된 기존 가정)

- [ ] **wire 헤더 개명**: `x-cf-d1-session-commit-token`(내부 workerd 명) → 공개 문서 헤더 **`x-d1-bookmark`**(요청·응답). 코드 `BOOKMARK_HEADER` 상수 변경.
- [ ] **세션을 B 전용으로**: §2 능력표에서 REST의 세션 지원 제거. `RestTransport.capabilities().supportsSessions=false`.
- [ ] **`supportsAtomicBatch`(REST)**: 라이브 프로브 후 값 확정(현재 `false` 잠정).
- [ ] **`meta` 필드 최신화**: `served_by` → `served_by_colo`/`served_by_primary`/`served_by_region`, `timings.sql_duration_ms`, `changed_db` 반영 (§4-4, `D1Meta`).

---

## 8. 참조 (분석 근거 파일:라인)

- `workerd src/cloudflare/internal/d1-api.ts` — 클라이언트 직렬화(`_send` L314), `bind()` 타입검증(L485), statement 메서드별 resultsFormat.
- `workers-sdk packages/miniflare/src/workers/d1/database.worker.ts:206` — `/query`,`/execute` 서버 계약, 응답 `{success, results, meta}`.
- `workers-sdk packages/miniflare/src/plugins/d1/index.ts` — `remote` 시 `d1:db:remote` 공유 서비스 배선.
- `workers-sdk packages/remote-bindings/templates/remoteBindings/ProxyServerWorker.ts:309` — 엣지 3방향 라우터.
- `workers-sdk packages/wrangler/src/d1/execute.ts:505,627` — 공개 REST API 경로/호출.
- `workers-sdk packages/workers-utils/src/config/binding-local-support.ts:22` — 바인딩 remote 지원 티어.

---

## 9. 확정 사실 (Cloudflare 공식 문서 리서치, 2026-07)

> 아래는 소스 분석이 아닌 **공식 문서**로 확정한 값이다. §1~§6의 소스 분석과 충돌하는 부분은 여기(§9)가 우선한다.

### 9-1. 상한 (limits) — 출처: developers.cloudflare.com/d1/platform/limits

| 항목 | 값 | 드라이버 반영 |
|---|---|---|
| 문장 길이 | 100,000 B (100KB) | `setString`/SQL 길이 가드, 초과 시 조기 `SQLException` |
| 바인딩 파라미터 수 | 100 / 문장 | 파라미터 수 가드 |
| BLOB·문자열·행 크기 | 2,000,000 B (2MB) | `setBytes`/`setString` 값 가드 |
| 컬럼 수 | 100 / 테이블 | `ResultSetMetaData` 가정 상한 |
| 쿼리 시간 | 30초 | 타임아웃 기본값 정렬 |
| 바인딩 호출당 쿼리 수 | 1,000 (Paid) / 50 (Free) | **B(프록시) `executeBatch` 청크 상한** |
| DB 크기 | 10GB (Paid) / 500MB (Free) | 참고 |

**미문서(추정 금지):** `db.batch` 문장 수 자체의 상한, REST `/query`·`/raw` 요청·응답 바이트 상한, 바인딩 응답 크기, SELECT 행-스캔 상한. → 응답 크기 기반 자동 페이징 불가; 큰 결과는 SQL `LIMIT/OFFSET` 행-수 휴리스틱으로 처리하고 "CF 미문서"를 명기.

### 9-2. REST 결과 형태 — 출처: api.cloudflare.com …/d1/…/raw, …/query

- 봉투: `{ result:[…], success, errors:[], messages:[] }`. 요소별 `{ success, meta, results }`.
- `/raw` → `results = { columns:string[], rows:(…)[][] }` (컬럼 보존). `/query` → `results = 행-객체 배열` (컬럼 순서·중복 손실). → **드라이버는 `/raw` 확정.**
- 배치: 본문에 `{ batch:[{sql,params}, …] }` **1급 폼** 존재("as a batch" 실행). 원자성 문구가 바인딩 문서만큼 강하지 않음 → **라이브 프로브 필요.**
- 에러: `{ code:number, message:string, documentation_url?, source.pointer? }`. **고정 코드 열거 없음** → SQLState는 메시지 휴리스틱.

### 9-3. 세션/일관성 — 출처: d1/best-practices/read-replication, d1/worker-api/d1-database

- bookmark = **순차 일관성(read-your-write)**, **트랜잭션 격리 아님.** (§5.3 확인 ✅)
- 공개 헤더는 **`x-d1-bookmark`** (요청·응답). `x-cf-d1-session-commit-token`은 내부 workerd 명 → **개명 필요.**
- **Sessions API는 바인딩 전용, REST 미제공.** → 세션 일관성은 **B(프록시)만.** (§2 정정)
- 인터랙티브 트랜잭션 없음(auto-commit), 원자성은 단일 `batch`에 한정. (§5.1 확인 ✅)
- `withSession` 부트스트랩: `first-unconstrained`(기본)/`first-primary`/`<bookmark>`; `getBookmark()`는 마지막 쿼리 버전(쿼리 없으면 null).

### 9-4. 파라미터/타입 — 출처: d1/worker-api, d1/worker-api/prepared-statements

- 바인딩 네이티브 변환: `null→NULL`, `Number→REAL/INTEGER`, `String→TEXT`, **`Boolean→INTEGER(0/1)`**, **`ArrayBuffer/뷰→BLOB`**, **BLOB 읽기→JS `Array`(바이트 정수 배열)**. `undefined→D1_TYPE_ERROR`.
  → JSON wire에는 Boolean/ArrayBuffer 충실 인코딩이 없으므로 **bool→1/0, blob→number 배열** 정규화가 두 전송로 모두에 안전하고, D1의 BLOB 읽기 표현과도 일치. (§4-2 근거 재서술: "D1에 boolean 없음"이 아니라 "JSON wire 인코딩 한계".)
- 파라미터: **순서형 `?NNN` / 익명 `?`만** 지원(명명 파라미터 미지원). (§4-2 확인 ✅)
- Date/Time 컨벤션: Cloudflare **미문서** → 드라이버 소유 결정. 기본값 **ISO-8601 TEXT**.

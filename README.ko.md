# d1-jdbc

[![CI](https://github.com/mack-erel/d1-jdbc/actions/workflows/ci.yml/badge.svg)](https://github.com/mack-erel/d1-jdbc/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/mack-erel/d1-jdbc.svg)](https://jitpack.io/#mack-erel/d1-jdbc)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[Cloudflare D1](https://developers.cloudflare.com/d1/)(엣지 SQLite)을 표준 JDBC로 접근하는 드라이버.
**런타임 의존성 0** — JDK만 사용한다 (`java.net.http` + 내장 JSON 코덱).

English documentation: [README.md](README.md)

D1은 어디서나 동일한 `{sql, params}` HTTP 계약을 쓴다. 이 드라이버는 그 계약을
JDBC 표면 아래에 재현하며, 교체 가능한 두 전송로를 제공한다:

| 전송로 | URL 스킴 | 배포 필요 | 원자적 배치 | 세션(read-your-write) |
|---|---|---|---|---|
| **REST** — 공개 D1 REST API | `jdbc:cloudflare-d1:rest://…` | 없음 | ✅ (`{batch:[…]}`, 실측 확정) | ❌ (REST 미제공) |
| **Proxy** — 셀프 배포 Worker | `jdbc:cloudflare-d1:proxy://…` | Worker 1개 ([`proxy/`](proxy/)) | ✅ (`db.batch()`) | ✅ (`x-d1-bookmark`) |

GUI 도구에서 동작 확인: **DBeaver**로 실 D1 데이터베이스 브라우징(테이블·컬럼·PK·데이터)
검증 완료 — [DBeaver 설정](#dbeaver--gui-도구) 참조.

## 설치

[JitPack](https://jitpack.io/#mack-erel/d1-jdbc) 사용:

```kotlin
// build.gradle.kts
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.mack-erel:d1-jdbc:v0.1.0")
}
```

## 빠른 시작

```java
// REST 전송로: 배포 없이 Account · D1 · Edit 스코프 API 토큰만 있으면 됨.
String url = "jdbc:cloudflare-d1:rest://<account_id>/<database_id>?token=<API_TOKEN>";

try (Connection conn = DriverManager.getConnection(url)) {
    try (PreparedStatement ps =
            conn.prepareStatement("INSERT INTO users(name, active) VALUES (?, ?)")) {
        ps.setString(1, "alice");
        ps.setBoolean(2, true);      // INTEGER 1/0으로 저장 (D1 컨벤션)
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

드라이버는 `ServiceLoader`로 자동 등록된다 — `Class.forName` 불필요.

### JDBC URL

```
jdbc:cloudflare-d1:rest://<account_id>/<database_id>?token=<API_TOKEN>
jdbc:cloudflare-d1:proxy://<worker-host>[/<base-path>]?token=<SHARED_SECRET>
```

선택 속성(쿼리스트링 또는 `Properties`): `connectTimeoutMillis`(기본 10000),
`requestTimeoutMillis`(기본 30000), `apiBase`(REST), `scheme`(proxy, 기본 `https`).
Username/password는 사용하지 않는다 — URL의 토큰이 곧 자격증명이다.

### 트랜잭션과 배치

D1에는 **인터랙티브 트랜잭션이 없다** (HTTP 요청마다 auto-commit).
드라이버는 D1이 실제로 보장하는 것 위에 JDBC를 매핑한다:

- `setAutoCommit(false)` → 쓰기를 클라이언트에 버퍼링; `commit()` 시 **하나의
  원자적 배치**로 전송(두 전송로 모두 all-or-nothing); `rollback()`은 버퍼 폐기.
  수동 트랜잭션 중의 읽기는 버퍼된 쓰기를 보지 못한다.
- `executeBatch()`는 1,000문장(D1 상한) 단위로 청크 분할 — 청크 내부는 원자적,
  전체는 아님.
- 1,000문장을 넘는 수동 트랜잭션은 몰래 쪼개지 않고 거부한다.

### 상한 (클라이언트에서 조기 검증)

| 항목 | 값 |
|---|---|
| SQL 문장 길이 | 100,000 바이트 |
| 문장당 바인딩 파라미터 | 100개 |
| 문자열/BLOB 값 크기 | 2,000,000 바이트 |
| 원자적 배치당 문장 수 | 1,000개 |

## DBeaver / GUI 도구

`DatabaseMetaData` 카탈로그 조회(`getTables`, `getColumns`, `getPrimaryKeys`,
`getIndexInfo`, `getImportedKeys` 등)가 구현되어 있어 범용 JDBC 도구가 D1 스키마를
탐색할 수 있다.

1. **Database → Driver Manager → New**: *Class Name*에
   `dev.mackerel.d1jdbc.D1Driver`, *Libraries*에 드라이버 jar 추가.
2. *URL template*: `jdbc:cloudflare-d1:rest://{account_id}/{database_id}?token={token}`.
3. 새 연결 → 완성된 JDBC URL 붙여넣기. **username/password는 빈칸**
   (*No authentication* 체크 시 재질문 없음).

## 설계 문서

- [`docs/DESIGN.md`](docs/DESIGN.md) — wire 계약 분석(`workers-sdk`/`workerd` 소스),
  JDBC 매핑 규격, 공식 문서로 확정한 상한·정정(§9) — 전부 실 D1로 실측 검증.
- [`proxy/`](proxy/) — 전송로 B용 Worker (한 번 배포, 모든 JVM에서 재사용).

## 테스트

- **오프라인**: 목 전송로 기반 113개 단위 테스트 — `./gradlew test` 또는
  [CI](.github/workflows/ci.yml)의 javac + JUnit-console 경로.
- **라이브 e2e (opt-in)**: 실 D1 대상 15개 테스트. 크리덴셜이 없으면 자동
  스킵(실패 아님) — [`docs/E2E.md`](docs/E2E.md) 참조.

## 상태와 로드맵

동작하는 드라이버이며 두 전송로 모두 실 D1로 검증됨. 프로덕션 실전 투입 이력은
아직 없음. 알려진 갭: 스트리밍 커서 없음(결과 전체 materialize — `LIMIT/OFFSET`
페이징 권장), 프록시 전송로에서 동명 결과 컬럼 축약(REST는 완전 충실), 인터랙티브
트랜잭션 없음(드라이버가 아닌 D1 플랫폼 제약).

## 라이선스

[MIT](LICENSE)

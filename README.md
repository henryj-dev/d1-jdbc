# d1-jdbc

Cloudflare D1(엣지 SQLite)을 표준 JDBC로 접근하기 위한 드라이버.

D1의 `{sql, params}` HTTP 계약을 JDBC 표면 아래에 재현하며, 두 전송로를 Transport 추상화로 지원한다:

- **REST** — Cloudflare 공개 D1 REST API (무배포 baseline)
- **Proxy** — 셀프 배포 프록시 Worker (원자적 배치/트랜잭션 등 고급 기능)

## 상태

설계 단계. 구현 전.

- 설계 문서: [`docs/DESIGN.md`](docs/DESIGN.md) — 프로토콜 분석, 전송로 비교, JDBC 매핑 규격, 제약, 프록시 Worker 스펙.

## E2E 테스트 (opt-in, 실제 D1)

실제 Cloudflare D1에 네트워크로 붙는 end-to-end 테스트는 **opt-in**이다. 크리덴셜이 없으면
모든 e2e 테스트는 **SKIP**(실패 아님)되어 오프라인 스위트에 영향이 없다. 환경변수 또는
gitignore된 `e2e.local.properties`로 크리덴셜을 공급하면 실행된다.

- 실행 방법·크리덴셜 획득(최소 권한 `Account · D1 · Edit` 토큰, `wrangler d1 create`,
  프록시 배포): [`docs/E2E.md`](docs/E2E.md)
- 템플릿: [`e2e.local.properties.example`](e2e.local.properties.example)

## 다음 단계

`docs/DESIGN.md`의 "열린 항목"과 착수 순서(프록시 Worker / 자바 스켈레톤) 참조.

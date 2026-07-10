# ADR 0092 — 멀티포드 MCP 세션 어피니티: Traefik sticky 쿠키

- 상태: 채택 (2026-07-10)
- 관련: 0018(streamable-HTTP transport), 0019(stateless-spec 라이브러리 소유), 0042(세션 transport lookup), 0088(HA 2-replica/HPA/PDB)

## 배경 (문제)
ssuai-backend를 HA로 2 replica 운영(ADR 0088)한 뒤, MCP 클라이언트(ssu-agent, 데스크톱 클라이언트 등)가 간헐적으로 "Session terminated / Session not found"로 실패했다. 특히 ssu-agent는 기동 시 `build_supervisor_graph`에서 MCP 툴을 eager 로딩(`get_tools`)하는데, initialize 직후 tools/list가 실패하며 startup abort → CrashLoopBackOff가 결정적으로 재현됐다.

근본원인: MCP streamable-HTTP의 **transport 세션이 pod-local**이다. Spring AI MCP 라이브러리(MCP SDK 0.18.3)의 `WebMvcStreamableServerTransportProvider`가 세션을 JVM 내부 `ConcurrentHashMap<String, McpStreamableServerSession>`에 보관하고, 세션 객체는 살아있는 SSE 스트림 핸들을 들고 있다. 모든 MCP 클라이언트는 Traefik 인그레스(ssumcp.duckdns.org)를 통해 접속하는데, Traefik은 nativeLB가 기본 off라 **Service의 kube-proxy LB를 우회해 pod endpoint로 직접 라운드로빈**한다. 그 결과 한 세션의 연속 요청(initialize · GET SSE 스트림 · notifications/initialized · tools/list)이 두 pod에 분산되고, 세션이 없는 pod가 "Session terminated"를 반환한다.

## 고려한 대안과 기각 사유
1. **Redis 분산 세션 스토어**: transport 세션을 Redis로 외부화. 기각 — MCP SDK 0.18.3에 pluggable 세션 스토어 SPI가 없고(오직 `setSessionFactory`로 생성만 제어), 세션 객체가 직렬화 불가한 라이브 SSE 스트림 핸들을 보유한다. 외부화하려면 라이브러리 transport provider를 통째로 재구현해야 하는데, ADR 0019가 "transport는 spring-ai-mcp 라이브러리 소유"로 못박았고 고위험·고비용이다. (앱 레벨 MCP **auth** 세션은 이미 Postgres로 외부화되어 크로스포드 안전 — 남은 pod-local은 이 transport 맵 하나뿐이었다.)
2. **Service `sessionAffinity: ClientIP`**: 기각 — Traefik이 Service LB를 우회(nativeLB off)하므로 실제 인그레스 경로에 효과가 없다. nativeLB를 켜도 인그레스 트래픽의 소스 IP가 Traefik pod 하나로 collapse되어 전 외부 트래픽이 단일 pod에 몰린다(로드 불균형).
3. **인그레스 컨트롤러 교체(nginx `upstream-hash-by` Mcp-Session-Id 헤더 해시)**: 기각 — 단일노드 prod의 기본 CNI/인그레스(Traefik)를 교체하는 것은 과도한 리스크·범위. Traefik은 헤더 기반 consistent-hash를 네이티브 지원하지 않는다.
4. **replica 1로 축소**: 기각 — HA/HPA 서사(ADR 0088) 후퇴.

## 결정
**Traefik 쿠키 기반 sticky 세션**을 ssuai-backend Service 어노테이션으로 활성화한다.
- `traefik.ingress.kubernetes.io/service.sticky.cookie: "true"`, `...cookie.name: mcp_lb_affinity`, `secure`/`httponly` 설정.
- Traefik이 첫 응답에 쿠키를 주입하고 이후 같은 클라이언트 요청을 동일 backend pod로 고정한다. backend가 unhealthy가 되면 정상 LB로 폴백하며 쿠키를 재작성한다.

## 동작 원리
클라이언트 → Traefik 인그레스 → (sticky 쿠키로 선택된) 고정 pod. MCP 스트리머블 HTTP 클라이언트는 세션 컨텍스트 동안 단일 httpx.AsyncClient(쿠키 jar 유지)를 재사용하므로, 서버-투-서버 클라이언트도 Set-Cookie를 후속 요청에 되돌려보내 한 pod에 고정된다. POST 요청과 GET SSE 스트림이 모두 같은 쿠키를 지니므로 straddle이 사라진다.

## 결과 / 검증
- ssu-agent의 eager get_tools startup이 성공 → CrashLoopBackOff 해소, 롤아웃 정상 완료.
- 리스크 낮음: LB 계층 설정 변경만, 애플리케이션/라이브러리 코드 무변경. 쿠키를 무시하는 클라이언트가 있어도 기존과 동일(악화 없음).
- 한계: pod 단위가 아닌 클라이언트 단위 고정이므로 세션 이동(pod 재기동)은 자연 폴백에 의존. transport 세션의 진짜 stateless화는 라이브러리(spring-ai-mcp)의 stateless-spec 지원을 기다린다(ADR 0019 후속 관찰 항목).

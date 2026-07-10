# ADR 0094 — 제로트러스트 FQDN 이그레스: Cilium eBPF (랩 검증, prod는 게이트)

- 상태: 채택 (2026-07-11) — 랩 검증 완료, prod 적용은 별도 GO 게이트(Track B)
- 관련: 0088(HA 2-replica/HPA/PDB), 0092(Traefik 세션 어피니티), 인프라=단일노드 k3s(OCI Ampere ARM, 2 OCPU/12GB, flannel VXLAN + Traefik, failover 없음)

## 배경 (문제)
캠퍼스 MCP가 여러 외부 목적지(도서관 Pyxis `oasis.ssu.ac.kr`, u-SAINT, LLM/임베딩 프로바이더)로 이그레스한다. 목표(Phase 3, 포트폴리오 간판) = **아이덴티티 기반·DNS 이름 단위 제로트러스트 이그레스** — 각 파드가 허용된 FQDN에만 나갈 수 있게 해, 예컨대 탈취된 에이전트가 임의 C2로 exfil하지 못하게 한다. 제약: **단일노드·failover 없음** → 라이브 노드에서 CNI를 잘못 교체하면 전면 장애.

## 고려한 대안과 기각 사유
1. **라이브 노드에서 flannel→Cilium 전체 교체**: 기각 — 최고위험(전 파드 재시작, 교체 중 CoreDNS/Traefik 다운, 오설정 시 호스트 네트워킹 절단, 롤백 대상 없음). 문서화된 무중단 마이그레이션(dual-overlay CiliumNodeConfig, 롤링 노드 교체)은 **전부 2노드+ 전제**라 단일노드엔 적용 불가. 포트폴리오 가치는 폐기용 랩에서 100% 확보 가능 → 라이브 리스크 이득 없음.
2. **표준 Kubernetes NetworkPolicy / kube-router**: 기각 — L3/L4 전용, **FQDN peer 표현 불가**(IP로만 강제). FQDN-selector KEP은 v1alpha1이고 저자들도 deny-by-FQDN을 unsafe로 규정. 프로젝트가 이미 기각(CDN IP 변동으로 CIDR allowlist 비현실적).
3. **FQDN 컨트롤러 오퍼레이터(nais/GKE FQDN)**: 기각 — FQDN→IP를 주기적으로 조회해 평범한 NetworkPolicy를 쓰는 cron resolver. TTL 갭/CDN 변동에 racy, stale allowlist. eBPF/아이덴티티 네트워킹이 아니라 resolver 해킹 → 간판 서사에 부적합.
4. **Squid/Envoy 이그레스 포워드 프록시**: 기각(예비 보류) — 클러스터 아웃티지 리스크는 최저지만 **앱 레이어 프록시** 시연이지 eBPF가 아님. 포드별 아이덴티티 약함, 단일노드에 이그레스 SPOF 추가, 배포마다 프록시 배선. "eBPF 데모"가 아니라 "이그레스만 잠그면 됨"으로 목표가 바뀌면 재고.
5. **멀티노드 dual-overlay 마이그레이션**: 기각 — 무중단 이점이 "다른 노드로 drain"에 의존 → 단일노드에선 "유일 노드 재부팅"으로 붕괴 = 어차피 전면 아웃티지.
6. **전면 보류**: 기각 — 리스크 0이나 가치 0, 간판 미배송. 랩-우선 트랙이 리스크 0으로 전 가치를 확보하므로 열위.

## 결정
**랩-우선 2-트랙, 명시적 분리:**
- **Track A (본 ADR, 완료)**: 폐기용 클러스터에서 **full Cilium 스택 + toFQDNs + Hubble**를 실증하고 아티팩트를 남긴다. prod 무변경.
- **Track B (별도 GO 게이트, 미착수)**: prod 실강제가 필요해지면 **flannel 위 Cilium generic-veth CNI-chaining**(kubeProxyReplacement=false, flannel이 IPAM+L3 유지, Traefik/kube-proxy 무변경)으로만. 롤백=CiliumNetworkPolicy 삭제. **라이브 노드 전체 CNI 교체는 절대 안 함.**

## 동작 원리 (검증한 메커니즘)
- **eBPF vs iptables**: 커널 훅이 netfilter 체인을 우회 → 규칙 증가에도 준상수 조회(선형 스캔 대비).
- **아이덴티티 기반**: Cilium이 FQDN마다 security identity를 부여하고 **파드 단위**로 강제.
- **DNS-aware(L7 DNS 프록시)**: 정책은 **DNS 가시성 규칙(kube-dns:53, `rules.dns matchPattern "*"`)을 먼저** 두어 프록시가 모든 조회를 보게 하고, 그 뒤 `toFQDNs` allowlist를 둔다. 이 페어링이 없으면 toFQDNs가 조용히 blackhole된다(핵심 gotcha). 파드가 이그레스 정책에 선택되는 순간 **default-deny egress**가 된다.
- 강제 지점은 이름 해석 후 L3/L4의 toFQDNs allowlist — TLS SNI가 아니라 **이름**으로 판정.

## 결과 (랩 실증 — kind K8s v1.35.0 + Cilium 1.19.5, kubeProxyReplacement=true, Hubble)
default-deny 하에서 backend(허용: `api.anthropic.com`)·agent(허용: `one.one.one.one`) 파드 프로브:

| 프로브 | 정책 前 | 정책 後 |
|---|---|---|
| backend → api.anthropic.com | 도달(404) | **FORWARDED** (404) |
| backend → api.openai.com | 도달(421) | **DROPPED** (exit 28) |
| backend → one.one.one.one | 도달(200) | **DROPPED** (exit 28) |
| agent → api.anthropic.com | 도달(404) | **DROPPED** — 아이덴티티 분리 |
| agent → one.one.one.one | 도달(200) | **FORWARDED** (200) |
| backend → 169.254.169.254 (메타데이터) | — | **DROPPED** — exfil 차단 |

- **포드별 아이덴티티 분리 실증**: 동일 호스트 `api.anthropic.com`가 backend엔 FORWARDED, agent엔 DROPPED (독립 재현됨).
- **default-deny 실재 확인**: `cilium-dbg endpoint list`에서 두 엔드포인트 egress Enforcement=Enabled (가정 아님).
- **Hubble 증거**: `demo/backend ... 162.159.140.245:443 (world) Policy denied DROPPED (TCP Flags: SYN)`.

## 한계 / 정직한 주의 (면접 포인트)
- **DNS 캐시는 모든 응답을 학습**한다(가시성 규칙 `matchPattern "*"`). 즉 `cilium-dbg fqdn cache`에 차단 대상 도메인도 뜬다 — **가시성 ≠ 허용**. 실제 강제는 toFQDNs allowlist가 L3/L4에서 수행(DROPPED 판정이 증거).
- **우회면**: DoH/DoT/하드코딩 IP를 쓰는 파드는 toFQDNs 평가를 안 받는다(default-deny면 drop=안전하나 allowlist 불가). 대응: `dnsPolicy: ClusterFirst` 강제 + kube-dns 외 :53 이그레스 차단.
- **fail-closed DNS 프록시 = 노드당 soft SPOF**: 에이전트 재시작마다 신규 외부연결 잠시 끊김 → 단일노드에선 나쁜 업그레이드가 미니 아웃티지. 이미지 pre-pull·버전 pin으로 완화.
- **chaining 모드 FQDN은 문서상 "limited"** + 버그 이력 → Track B 전에 폐기용 ARM VM에서 DNS 프록시 인터셉트를 먼저 검증해야 한다.

## 재현물
`docs/cilium-fqdn-egress/` 에 검증한 `policy.yaml`(참조용, **prod 미배포**)과 `RESULTS.md`(前/後 표) 보관. 랩=kind(비ArgoCD). Track B 착수 시 이 정책을 chaining 모드에서 PolicyAuditMode로 먼저 돌려 실제 프로바이더 서브도메인을 enumerate한 뒤 강제.

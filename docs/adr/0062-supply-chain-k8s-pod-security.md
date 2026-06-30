# ADR 0062 — 공급망 재현성(SHA 핀) + k8s pod securityContext

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-22 |
| 상태 | Accepted — 구현·배포(`d99a155`) |
| 범위 | `.github/workflows/*`(Actions SHA pin) / `gradle/wrapper`(checksum) / `Dockerfile`(base image digest) / `deploy/charts/ssuai-backend`(pod·container securityContext) |
| 연관 사건 | TROUBLESHOOTING 사건 17 |

---

## 배경 — 무슨 문제

두 부류의 공급망·런타임 하드닝이 빠져 있었다(외부 리뷰 #22 공급망, #23 k8s):

- **CI 재현성**: GitHub Actions를 `@v6` 같은 mutable tag로 참조 → 태그가 가리키는 커밋이 바뀌면 빌드가 조용히 달라지거나 악성 코드가 주입될 수 있다(태그는 재지정 가능). Gradle wrapper jar에 checksum 검증 없음. Docker base image를 mutable tag로 FROM.
- **k8s blast radius**: pod가 기본 ServiceAccount 토큰을 마운트(앱은 k8s API를 호출하지 않음), seccomp 미적용, privilege escalation 미차단.

## 결정

### 공급망 (재현성·무결성)

- **Actions를 40자 commit SHA로 핀** + `# vN` 주석(예: `actions/checkout@df4cb1c…  # v6`). 태그 재지정 공격을 무력화하면서 사람이 버전을 읽을 수 있게 유지.
- **Gradle wrapper checksum 검증**(`gradle-wrapper.properties`의 `distributionSha256Sum`/wrapper jar sha256) — 변조된 wrapper jar로 임의 코드 실행 차단.
- **Docker base image를 `@sha256:` digest로 핀**(rusaint-builder ubuntu:22.04, builder/runtime eclipse-temurin 21) — 이미지 태그 이동에도 동일 바이트 보장.
- release 워크플로의 테스트 복원.

### k8s pod/container securityContext

- pod: `automountServiceAccountToken: false`(앱이 k8s API 미호출 → blast radius 축소).
- container: `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `seccompProfile.type: RuntimeDefault`, `capabilities.drop`.
- **`readOnlyRootFilesystem: false`는 의도적**(주석 명시): LMS export worker가 임시 ZIP을 로컬에 쓰기 때문. ro-rootfs 적용은 emptyDir/writable mount 분리가 필요한 후속(signoff, security-followups.md).

## 대안과 기각 이유

- **Actions를 `@v6` tag로 유지**: 편하지만 mutable. 공급망 핵심 위협이라 SHA 핀이 표준. 기각.
- **Dependabot이 SHA를 자동 갱신**하도록 설정 → SHA 핀 + Dependabot이 PR로 갱신을 제안하는 조합이 재현성·최신성을 모두 만족. (Dependabot 메이저 PR은 별도 검토 — signoff.)
- **`readOnlyRootFilesystem: true` 강제**: LMS export 쓰기 경로가 깨져 기능 회귀. writable mount 분리 후속으로 미루고 false 유지 + 주석. 기각(지금은).
- **NetworkPolicy 동시 적용**: 오설정 시 backend↔postgres/redis/ingress 차단 = prod 아웃 + 자율 검증 불가. signoff로 분리(권장만). 기각(지금은).

## 동작 방식 / 검증

- 빌드 영향 값(Actions SHA·wrapper checksum·Docker digest)은 **CI가 검증**(green 확인).
- k8s securityContext는 **배포 후 pod 확인**(0-restart, health UP)으로 검증.
- 마지막 코드 PR로 머지.

## 예상 면접 질문

1. Actions를 tag가 아니라 commit SHA로 핀하는 이유는? 태그 재지정 공격이 무엇이고 SHA 핀이 어떻게 막나?
2. `readOnlyRootFilesystem`을 false로 둔 이유와, true로 가려면 무엇을 바꿔야 하나?
3. `automountServiceAccountToken: false`가 줄이는 blast radius는 구체적으로 무엇인가?
4. NetworkPolicy를 자율 적용하지 않고 signoff로 미룬 판단의 근거는?

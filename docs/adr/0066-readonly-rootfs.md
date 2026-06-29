# ADR 0066 — ssuai-backend read-only root filesystem 적용

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30 |
| 상태 | Accepted — 차트 적용, live u-SAINT/rusaint 검증 대기 |
| 범위 | `deploy/charts/ssuai-backend` |
| 연관 문서 | ADR 0062, `docs/security-followups.md` #2 |

---

## 배경

ADR 0062에서 k8s pod securityContext를 강화할 때 `readOnlyRootFilesystem`와 NetworkPolicy는 제외했다. 당시 판단은 두 가지였다.

- LMS export worker가 ZIP 생성 중 로컬 파일시스템에 임시 파일을 쓰는 것으로 보였고, writable mount를 분리하지 않은 상태에서 rootfs를 읽기 전용으로 바꾸면 자료 내보내기 기능이 깨질 수 있었다.
- NetworkPolicy는 오설정 시 backend와 Postgres/Redis/Ingress 사이의 통신을 차단해 prod 전체 장애로 이어질 수 있어 별도 검증 창구가 필요했다.

이번 ADR은 두 항목 중 read-only rootfs만 닫는다. NetworkPolicy는 여전히 클러스터 CNI 강제 여부와 연결성 검증이 필요하므로 후속 작업으로 남긴다.

## 왜 지금 안전한가

현재 LMS export 임시 경로는 `SSUAI_LMS_EXPORT_TEMP_DIR=/data/lms-export`이고, values에서는 `env.lmsExportTempDir: /data/lms-export`로 지정되어 있다. 이 경로는 `lms-export` PVC의 mountPath이므로 애플리케이션이 생성하는 ZIP과 export 캐시는 root filesystem이 아니라 writable PVC에 기록된다.

남은 rootfs 쓰기 후보는 앱 데이터가 아니라 런타임 scratch다.

- JVM scratch: 기본 `java.io.tmpdir` 사용자가 `/tmp`를 기대하는 코드와 라이브러리가 있다.
- JNA `jnidispatch`: rusaint UniFFI는 JNA를 통해 네이티브 호출을 수행한다. 실제 rusaint 공유 라이브러리인 `/usr/local/lib/librusaint_ffi.so`는 이미지에 포함되어 있고 읽기 전용 로드만 필요하다. 다만 JNA 자체의 `jnidispatch` native stub은 시작 시 classpath에서 임시 디렉터리로 풀어 로드할 수 있다.

따라서 root filesystem 전체를 writable로 둘 필요가 없다. `/tmp`만 `emptyDir`로 writable하게 제공하고, `java.io.tmpdir`와 `jna.tmpdir`를 모두 `/tmp`로 고정하면 JVM과 JNA scratch는 writable volume으로 빠지고 rootfs는 읽기 전용으로 닫힌다.

## 근거와 출처

- Kubernetes `securityContext`는 컨테이너 단위로 `readOnlyRootFilesystem`를 설정할 수 있다. 컨테이너 이미지 레이어 쓰기를 막는 defense-in-depth 제어로, 런타임에 써야 하는 경로는 별도 volume으로 분리해야 한다.  
  출처: <https://kubernetes.io/docs/tasks/configure-pod-container/security-context/>
- Kubernetes `emptyDir`는 pod가 노드에 할당될 때 생성되고 pod가 제거되면 함께 삭제되는 scratch volume이다. pod 내 컨테이너가 같은 임시 공간을 mount해서 쓸 수 있다.  
  출처: <https://kubernetes.io/docs/concepts/storage/volumes/#emptydir>
- JNA는 `jnidispatch`를 system path에서 찾지 못하면 classpath 리소스에서 임시 디렉터리로 추출해 로드할 수 있고, 자동 unpack을 막으려면 `jna.nounpack=true`를 사용한다. `jna.tmpdir`는 JNA 전용 임시 디렉터리 override다.  
  출처: <https://github.com/java-native-access/jna/blob/master/src/com/sun/jna/Native.java>

## 대안과 기각 이유

- **`jna.nounpack=true` + system `jnidispatch` 사전 설치**: rootfs 쓰기 없이 JNA stub을 시스템 경로에서만 로드하게 만들 수 있다. 그러나 이미지 빌드 단계에 JNA 버전과 플랫폼에 맞는 `jnidispatch`를 추가로 bake해야 하며, JNA 업그레이드 때 native stub 동기화 검증이 필요하다. 현재 문제는 scratch 경로 하나를 writable하게 제공하면 해결되므로 과한 변경이다.
- **LMS export PVC만 유지하고 `/tmp`를 mount하지 않기**: 앱 데이터 경로는 해결되지만 JVM/JNA scratch가 rootfs `/tmp`에 쓰려는 순간 startup 또는 첫 rusaint 호출이 실패할 수 있다. 읽기 전용 rootfs 적용 조건을 만족하지 못한다.
- **NetworkPolicy까지 같은 변경으로 적용**: read-only rootfs와 위험면이 다르다. NetworkPolicy는 CNI enforce 여부와 DB/Redis/Ingress 연결성 검증이 선행되어야 하므로 이번 커밋 범위에서 제외한다.

## 결정

Helm chart만 변경한다.

- `deploy/charts/ssuai-backend/templates/deployment.yaml`
  - `tmp` `emptyDir: {}` volume을 항상 만든다.
  - backend container에 `name: tmp`, `mountPath: /tmp` volumeMount를 항상 추가한다.
  - 기존 `lms-export` PVC와 mountPath는 그대로 유지하고, persistence gate도 변경하지 않는다.
- `deploy/charts/ssuai-backend/values.yaml`
  - `containerSecurityContext.readOnlyRootFilesystem: true`
  - `env.javaOpts`에 `-Djava.io.tmpdir=/tmp -Djna.tmpdir=/tmp`를 추가한다.

`runAsUser`, `fsGroup`, PVC 크기/스토리지클래스, seccomp, capabilities, privilege escalation 설정은 변경하지 않는다.

## 동작 방식

1. pod가 생성되면 kubelet이 pod-local `tmp` `emptyDir`를 만든다.
2. backend container는 이미지 rootfs를 read-only로 mount하고 `/tmp` 위치에만 `tmp` `emptyDir`를 덮어 mount한다.
3. Spring Boot는 기존 환경변수로 LMS export ZIP을 `/data/lms-export` PVC에 쓴다.
4. JVM과 JNA는 `JAVA_OPTS`에 들어간 `java.io.tmpdir=/tmp`, `jna.tmpdir=/tmp`를 따라 scratch 파일과 `jnidispatch` 추출물을 `/tmp` emptyDir에 쓴다.
5. rusaint UniFFI의 `/usr/local/lib/librusaint_ffi.so`는 이미지에서 읽기 전용으로 로드된다. 이 파일은 수정될 필요가 없다.

## 롤백

문제가 있으면 이 커밋을 revert한다. main에 revert가 올라가면 ArgoCD auto-sync/selfHeal이 차트를 이전 형태로 되돌린다. Deployment rolling update는 `maxSurge: 1`, `maxUnavailable: 0`이므로 새 pod가 Ready가 되지 않으면 기존 정상 pod를 내리지 않는다.

## 검증 계획

PR 단계:

- `helm lint deploy/charts/ssuai-backend`
- `helm template`으로 `readOnlyRootFilesystem: true`, `/tmp` `emptyDir`, `/tmp` volumeMount, 기존 `lms-export` PVC mount가 모두 렌더링되는지 확인한다.

배포 후:

- 새 backend pod가 Ready 상태인지 확인한다.
- authenticated u-SAINT/rusaint 호출이 read-only rootfs 환경에서도 성공하는지 확인한다. 이 호출이 JNA `jnidispatch` 추출과 `/usr/local/lib/librusaint_ffi.so` 로드를 함께 검증한다.

## 예상 면접 질문

1. read-only rootfs가 막는 공격과, writable volume을 함께 열어야 하는 이유는 무엇인가?
2. LMS export PVC와 `/tmp` emptyDir의 책임을 왜 나누었나?
3. JNA `jnidispatch`와 rusaint FFI shared library의 차이는 무엇이며, 어떤 파일이 실제로 쓰기를 요구하나?
4. `jna.nounpack=true`를 선택하지 않은 이유는 무엇인가?
5. NetworkPolicy를 같은 커밋에 넣지 않은 이유는 무엇인가?

# scripts/

Operational / spike scripts. Not part of the runtime backend or frontend.

## `spike-ssotoken-ttl.{ps1,sh}` — oasis Pyxis-Auth-Token TTL 측정

Task 13 §7 #2 spike. PR 13c (manual-paste capture flow)을 시작하기 전에
**captured token이 얼마나 오래 유효한지** 측정한다. 이 결과에 따라
`LibrarySessionStore` 의 영구 저장 정책 (Pyxis가 며칠 단위로 만료시키면
in-memory + 2h TTL이면 충분, 시간 단위면 AES-GCM + DB 영구 저장 필요)이
바뀐다.

> **2026-05-15 디버깅 노트.** 초기 spec은 인증이 `ssotoken` cookie라고
> 가정했지만, 실제로 Pyxis API는 **`Pyxis-Auth-Token` request header**
> 로 인증한다. ssotoken cookie 만 보내면 어느 endpoint를 쳐도
> `needLogin` 응답이 온다. Endpoint도 spec의 `/pyxis-api/api/smuf/reading-rooms`
> 가 아니라 `/pyxis-api/1/seat-rooms?smufMethodCode=PC&branchGroupId=1` 이
> 진짜 SPA가 호출하는 URL이다. 스크립트와 spec은 이에 맞게 수정됨.
> Task 13 spec §4 endpoints table과 §5 architecture diagram도 같이 갱신.

### 1. 토큰 캡처 (Pyxis-Auth-Token)

1. 크롬/엣지에서 `https://oasis.ssu.ac.kr/library-services/` 접속
2. 우상단 로그인 → 학번/비번 입력 (**"로그인 상태 유지" 체크** — long-lived
   토큰 받을 수 있는지 같이 확인)
3. 로그인 성공한 페이지에서 F12 → **Network** 탭
4. 필터: **Fetch/XHR** 선택, 검색창에 `pyxis-api` 입력
5. 새로고침(F5)해서 API 호출 발생시킴
6. 목록의 `/pyxis-api/1/seat-rooms?…` 등 아무 요청 클릭
7. **Headers** 패널 → **Request Headers** → `Pyxis-Auth-Token` 값 복사

(devtools Application → Cookies 에서 `ssotoken` 보는 옛 방식 X. 거긴
SPA 로딩용 cookie이고 API 인증과는 별개다.)

### 2. 스크립트 실행

**PowerShell (권장 — Windows 기본 셸):**
```powershell
$env:OASIS_PYXIS_TOKEN = "<paste 값>"
.\scripts\spike-ssotoken-ttl.ps1
```

**Bash (Git Bash / WSL / macOS / Linux):**
```bash
export OASIS_PYXIS_TOKEN="<paste 값>"
./scripts/spike-ssotoken-ttl.sh
```

기본 폴링 주기는 5분 (`OASIS_TTL_INTERVAL_SEC` env로 변경). PS 창은
켜둔 채로 다른 작업 해도 무방. 만료될 때까지 그냥 두면 자동 종료.

(legacy `OASIS_SSOTOKEN` 환경변수 이름도 호환되지만 새 캡처는
`OASIS_PYXIS_TOKEN` 사용 권장.)

### 3. 출력 해석

콘솔/로그 라인 형식:
```
2026-05-15T20:50:00+09:00 poll=1 status=ok bodylen=2157
2026-05-15T20:55:00+09:00 poll=2 status=ok bodylen=2157
...
2026-05-15T22:55:00+09:00 poll=25 status=expired elapsed=7500s
```

정상 응답이면 `bodylen` 이 **수 KB ~ 수십 KB** (실제 seat 데이터).
`bodylen=104` 처럼 작으면 needLogin JSON 응답이라는 신호 — 스크립트가
이걸 자동으로 감지해서 `status=expired` 로 찍는다.

마지막 `expired` 라인의 `elapsed` 가 **active-use 환경에서의 TTL** 이다.
(매 5분마다 쿼리하니 sliding session이면 갱신될 수 있음. 진짜 idle TTL을
측정하려면 폴링 안 하고 만료 추정 시점에 한 번만 쳐서 확인.)

### 4. 결과 해석 → 결정 트리

| 측정 TTL | 함의 | 구현 결정 |
|----------|------|-----------|
| **≥ 1주일** | 학기당 1~2회 재paste, UX 거의 무감각 | `LibrarySessionStore` H2 영구 + AES-GCM 저장. 갱신 endpoint 탐색 불필요. |
| **수 시간 ~ 1일** | 매일 1회 재paste | 위와 동일하되 만료 시 silent 401 → 프론트가 재캡처 모달 띄움 |
| **< 2시간 (sliding 가능)** | 거의 매 사용 재paste = UX 실패 | (a) Pyxis token 갱신 endpoint 탐색 (b) "로그인 상태 유지" 옵션 사용 매뉴얼 (c) 그래도 짧으면 spec §12의 D안 (u-SAINT 우선) 또는 F안 (비번 저장 opt-in) 재논의 |

### 5. 보안 주의

- `OASIS_PYXIS_TOKEN` 은 **로컬 env var 로만** 다루기. `.env` 같은 디스크
  파일에 적지 말 것 (실수로 commit 가능). 셸 history 도
  `set +o history` / `HISTCONTROL=ignorespace` 권장.
- 측정 종료 후 즉시 oasis 로그아웃 (서버 측에서 토큰 무효화)
- 디버깅 도중 토큰 값을 채팅이나 외부 도구에 paste한 적이 있으면 그 토큰은
  사용 종료, 로그아웃 후 새로 캡처.
- 로그 파일 `*.log` 는 `.gitignore` 처리됨. 그래도 commit 전에
  `git status` 확인.
- 스크립트는 토큰 **fingerprint (sha256 앞 8자)** 만 출력. 로그/콘솔에
  raw 토큰은 절대 안 찍힘.

### 6. 측정 끝나면 알려줘

만료 시각 `elapsed=NNNs` 만 공유. 토큰 값은 절대 채팅에 붙이지 말 것.
그 숫자로 4단계 결정 트리에 따라 PR 13c 설계 확정한다.

# public live deploy 수동 준비 체크리스트

이 문서는 ssuAI MCP 서버를 공개 배포하기 전에 **직접 만들어야 하는 것들**을
한국어로 정리한 체크리스트입니다.

주의:

- 여기에 비밀번호, API token, DuckDNS token, SSH private key를 적지 마세요.
- 실제 secret 값은 비밀번호 관리자, Oracle/Vercel/GitHub UI, 또는 VM 내부에만 둡니다.
- `<...>` 로 된 값은 나중에 직접 채워야 하는 placeholder입니다.

---

## 0. 최종 목표

최종적으로 아래 URL들이 생기면 됩니다.

```text
Backend health:
https://<BACKEND_HOST>/actuator/health

Public MCP server:
https://<BACKEND_HOST>/mcp

Frontend:
https://<VERCEL_URL>
```

예상 예시:

```text
Backend health:
https://ssumcp.duckdns.org/actuator/health

Public MCP server:
https://ssumcp.duckdns.org/mcp

Frontend:
https://ssuai.vercel.app
```

---

## 1. 직접 만들어야 하는 계정

### 1.1 Oracle Cloud 계정

용도: backend MCP 서버가 올라갈 VM을 만들기 위해 필요합니다.

만드는 방법:

1. 브라우저에서 <https://cloud.oracle.com> 접속
2. `Start for free` 또는 무료 계정 생성 선택
3. 이메일, 카드 인증, 전화번호 인증 진행
4. Home region 선택 시 가능하면 한국 region 선택
   - 서울이 가능하면 `ap-seoul-1`
   - 현재 로컬 OCI 설정은 `ap-chuncheon-1` 로 잡혀 있음
5. 가입 완료 후 Oracle Cloud Console 접속 확인

주의:

- 유료 리소스를 만들지 않도록 Always Free 범위만 사용합니다.
- 지금 자동화 스크립트는 `VM.Standard.A1.Flex` ARM VM을 만들려고 시도합니다.

체크:

- [ ] Oracle Cloud 계정 생성 완료
- [ ] Oracle Console 로그인 가능
- [ ] OCI CLI 설정 완료
- [ ] `python "C:\Users\akftj\Desktop\oracle_macro.py" --dry-run` 성공

---

### 1.2 DuckDNS 계정

용도: Oracle VM의 public IP에 사람이 읽을 수 있는 도메인을 붙이기 위해 필요합니다.

만드는 방법:

1. 브라우저에서 <https://www.duckdns.org> 접속
2. GitHub 또는 Google로 로그인
3. 로그인 후 DuckDNS dashboard에서 `sub domain` 입력칸 찾기
4. 원하는 이름 입력

추천 이름:

```text
ssumcp
```

생성되면 backend host는 이렇게 됩니다.

```text
ssumcp.duckdns.org
```

주의:

- DuckDNS token은 secret입니다.
- token을 repo 파일에 절대 적지 마세요.
- token은 나중에 VM 안의 cron 설정에만 넣거나, 개인 비밀번호 관리자에 저장하세요.

체크:

- [ ] DuckDNS 로그인 완료
- [ ] `ssumcp` 또는 원하는 subdomain 확보
- [ ] DuckDNS token을 repo 밖에 안전하게 저장
- [ ] 사용할 backend host 결정: `<BACKEND_HOST>`

내가 채울 값:

```text
<BACKEND_HOST>=ssumcp.duckdns.org
```

---

### 1.3 Vercel 계정

용도: frontend 웹 대시보드를 배포하기 위해 필요합니다.

만드는 방법:

1. 브라우저에서 <https://vercel.com> 접속
2. GitHub 계정으로 로그인
3. Vercel이 GitHub repository를 읽을 수 있도록 권한 허용
4. 나중에 `New Project` 에서 이 repo를 import

체크:

- [ ] Vercel 계정 생성 완료
- [ ] GitHub 연결 완료
- [ ] Vercel에서 ssuAI repo가 보임

---

## 2. Oracle VM 생성 전까지 할 일

### 2.1 Oracle VM 자동 생성 macro 켜두기

명령 프롬프트 또는 PowerShell에서 실행:

```powershell
python "C:\Users\akftj\Desktop\oracle_macro.py" --size 1:6 --size 2:12 --size 4:24 --shuffle --retry-seconds 120
```

이 명령은:

- 1 OCPU / 6 GB
- 2 OCPU / 12 GB
- 4 OCPU / 24 GB

순서로 가능한 후보를 계속 시도합니다.

성공하면 이런 식으로 나와야 합니다.

```text
Instance is running
- name: ssumcp
- state: RUNNING
- public_ip: <VM_PUBLIC_IP>
- ssh: ssh ubuntu@<VM_PUBLIC_IP>
```

체크:

- [ ] macro 실행 중
- [ ] 성공하면 public IP 기록

내가 채울 값:

```text
<VM_PUBLIC_IP>=
```

---

### 2.2 GitHub Actions Docker image 확인

용도: Kubernetes가 당겨갈 backend Docker image가 GitHub Container Registry에 있어야 합니다.

확인 방법:

1. GitHub에서 ssuMCP repository 접속
2. 상단 `Actions` 탭 클릭
3. `CI` workflow 클릭
4. 최신 `main` push 실행 결과 확인
5. 아래 job이 성공했는지 확인

```text
Backend image (ghcr.io, ARM64)
```

체크:

- [ ] GitHub Actions `CI` 성공
- [ ] `Backend image (ghcr.io, ARM64)` 성공

주의:

- 아직 내 로컬 변경을 배포하려면 commit 후 `main`에 push해야 합니다.
- push 전에는 GitHub Actions가 새 image를 만들지 않습니다.

---

### 2.3 GHCR package public으로 변경

용도: k3s가 `ghcr.io/hoeongj/ssumcp:latest` image를 pull할 수 있게 하기 위함입니다.

만드는 방법:

1. 브라우저에서 GitHub 접속
2. 오른쪽 위 프로필 클릭
3. `Your packages` 또는 아래 URL 접속

```text
https://github.com/hoeongj?tab=packages
```

4. `ssumcp` package 클릭
5. `Package settings` 클릭
6. `Danger Zone` 또는 visibility 설정 영역에서 `Change visibility` 클릭
7. `Public` 으로 변경

체크:

- [ ] `ghcr.io/hoeongj/ssumcp` package가 보임
- [ ] package visibility가 `Public`

실패하면 나중에 보이는 증상:

```text
ImagePullBackOff
```

---

## 3. Vercel frontend 미리 만들기

Oracle VM이 아직 없어도 Vercel project는 먼저 만들 수 있습니다.

### 3.1 Vercel project 생성

만드는 방법:

1. <https://vercel.com> 접속
2. Dashboard에서 `Add New...` 또는 `New Project` 클릭
3. GitHub repo 목록에서 `ssuAI` 선택
4. `Import` 클릭
5. Project 설정에서 `Root Directory`를 아래로 설정

```text
frontend
```

6. Framework preset은 Next.js로 자동 인식되는지 확인

체크:

- [ ] Vercel project 생성 시작
- [ ] Root Directory가 `frontend`

---

### 3.2 Vercel 환경변수 설정

Vercel project 설정 화면에서 environment variable 추가:

```text
NEXT_PUBLIC_SSUAI_API_BASE=https://<BACKEND_HOST>
```

예시:

```text
NEXT_PUBLIC_SSUAI_API_BASE=https://ssumcp.duckdns.org
```

주의:

- backend가 아직 안 떠 있으면 frontend 화면에서 API 호출은 실패할 수 있습니다.
- 그래도 Vercel project URL을 먼저 확보하는 게 목적입니다.

체크:

- [ ] `NEXT_PUBLIC_SSUAI_API_BASE` 설정
- [ ] Vercel deploy 실행
- [ ] Vercel production URL 확인

내가 채울 값:

```text
<VERCEL_URL>=https://ssuai.vercel.app
```

예:

```text
https://ssuai.vercel.app
```

---

## 4. Oracle VM이 만들어진 뒤 할 일

### 4.1 DuckDNS에 VM IP 연결

만드는 방법:

1. <https://www.duckdns.org> 접속
2. 로그인
3. 내가 만든 subdomain 행 찾기
4. `current ip` 또는 IP 입력칸에 `<VM_PUBLIC_IP>` 입력
5. `update ip` 클릭

확인 명령:

```powershell
nslookup <BACKEND_HOST>
```

정상이라면 `<VM_PUBLIC_IP>`가 나와야 합니다.

체크:

- [ ] DuckDNS IP를 VM public IP로 변경
- [ ] `nslookup <BACKEND_HOST>` 결과가 `<VM_PUBLIC_IP>`

---

### 4.2 Oracle 보안 규칙 열기

Oracle Cloud Console에서 해야 합니다.

만드는 방법:

1. Oracle Cloud Console 접속
2. 왼쪽 메뉴에서 `Compute` → `Instances`
3. 생성된 VM 클릭
4. VM 상세 화면에서 `Primary VNIC` 또는 subnet 링크 클릭
5. 연결된 `Security List` 또는 `Network Security Group` 확인
6. Ingress Rule 추가

추가할 rule:

```text
Source: 0.0.0.0/0
Protocol: TCP
Destination Port: 22
```

```text
Source: 0.0.0.0/0
Protocol: TCP
Destination Port: 80
```

```text
Source: 0.0.0.0/0
Protocol: TCP
Destination Port: 443
```

체크:

- [ ] TCP 22 열림
- [ ] TCP 80 열림
- [ ] TCP 443 열림

---

### 4.3 VM에 SSH 접속

PowerShell 또는 터미널에서:

```bash
ssh ubuntu@<VM_PUBLIC_IP>
```

처음 접속할 때 fingerprint 확인 질문이 나오면 `yes` 입력.

체크:

- [ ] SSH 접속 성공

---

### 4.4 VM에 k3s 설치

VM 안에서 실행:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
curl -sfL https://get.k3s.io | sh -
sudo k3s kubectl get nodes
```

정상 결과 예시:

```text
NAME      STATUS   ROLES
...
Ready
```

체크:

- [ ] UFW 22/80/443 허용
- [ ] k3s 설치 완료
- [ ] node 상태 `Ready`

---

### 4.5 kubeconfig를 내 PC에 연결

VM 안에서 실행:

```bash
sudo cat /etc/rancher/k3s/k3s.yaml
```

출력된 내용을 내 PC의 kubeconfig 파일에 저장:

```text
C:\Users\akftj\.kube\config
```

그 안의 server 값을 바꿉니다.

기존:

```yaml
server: https://127.0.0.1:6443
```

변경:

```yaml
server: https://<VM_PUBLIC_IP>:6443
```

내 PC에서 확인:

```powershell
kubectl get nodes
```

체크:

- [ ] kubeconfig 저장
- [ ] server IP를 VM public IP로 변경
- [ ] 내 PC에서 `kubectl get nodes` 성공

---

## 5. Kubernetes 배포 준비

### 5.1 cert-manager 설치

내 PC에서 실행:

```powershell
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
kubectl wait --for=condition=available --timeout=120s -n cert-manager deploy/cert-manager deploy/cert-manager-webhook deploy/cert-manager-cainjector
```

체크:

- [ ] cert-manager 설치 완료

---

### 5.2 적용용 manifest 생성

repo root에서 실행:

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/prepare-live-deploy.ps1 `
  -BackendHost <BACKEND_HOST> `
  -FrontendOrigin <VERCEL_URL> `
  -OperatorEmail <OPERATOR_EMAIL>
```

예시:

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/prepare-live-deploy.ps1 `
  -BackendHost ssumcp.duckdns.org `
  -FrontendOrigin https://ssuai.vercel.app `
  -OperatorEmail your-email@example.com
```

이 명령은 아래 폴더를 만듭니다.

```text
deploy/generated/gitops-breakglass
```

이 폴더는 gitignore 되어 있습니다. 개인 배포값이 들어가기 때문입니다.

체크:

- [ ] `deploy/generated/gitops-breakglass` 생성됨
- [ ] backend host가 맞음
- [ ] frontend origin이 맞음
- [ ] operator email이 맞음

내가 채울 값:

```text
<OPERATOR_EMAIL>=
```

---

### 5.3 backend manifest 적용

repo root에서 실행:

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/apply-live-deploy.ps1
```

이 스크립트는 아래 순서로 적용합니다.

```text
clusterissuer.yaml
backend.yaml
```

체크:

- [ ] manifest apply 성공
- [ ] rollout 성공
- [ ] pod 상태 Running
- [ ] certificate 상태 Ready

직접 확인:

```powershell
kubectl -n ssuai-prod get pods,svc,ingress
kubectl get certificate -A
```

---

## 6. 최종 검증

### 6.1 backend health 확인

```powershell
curl.exe -i https://<BACKEND_HOST>/actuator/health
```

정상:

```json
{"status":"UP"}
```

체크:

- [ ] health 응답 200
- [ ] HTTPS 자물쇠 정상

---

### 6.2 REST API 확인

```powershell
curl.exe https://<BACKEND_HOST>/api/meals/today
```

체크:

- [ ] JSON 응답이 옴
- [ ] `data`
- [ ] `traceId`
- [ ] `error`가 null 또는 정상적인 error envelope

---

### 6.3 MCP Streamable HTTP 확인

```powershell
$body = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"manual-verify","version":"1.0.0"}}}'
curl.exe -i -X POST https://<BACKEND_HOST>/mcp `
  -H "Content-Type: application/json" `
  -H "Accept: application/json, text/event-stream" `
  --data-raw $body
```

체크:

- [ ] HTTP 성공 응답 또는 MCP JSON-RPC 응답 확인
- [ ] initialize 결과에 서버 정보가 포함됨

---

### 6.4 frontend 확인

브라우저에서 접속:

```text
<VERCEL_URL>
```

체크:

- [ ] 페이지가 열림
- [ ] 오늘의 학식 카드가 backend에서 데이터 가져옴
- [ ] 주간 학식 카드가 backend에서 데이터 가져옴
- [ ] 기숙사 식단 카드가 backend에서 데이터 가져옴
- [ ] 시설 검색이 동작함

---

## 7. Claude Desktop / Cursor에서 MCP 서버 등록

public MCP URL:

```text
https://<BACKEND_HOST>/mcp
```

Claude Desktop에서 URL 기반 remote MCP를 지원하면:

```json
{
  "mcpServers": {
    "ssuai": {
      "url": "https://<BACKEND_HOST>/mcp"
    }
  }
}
```

Cursor MCP 설정 예시:

```json
{
  "mcpServers": {
    "ssuai": {
      "url": "https://<BACKEND_HOST>/mcp"
    }
  }
}
```

체크:

- [ ] MCP client에서 `ssuai` server 연결됨
- [ ] `get_today_meal` tool 보임
- [ ] `get_meal_by_date` tool 보임
- [ ] `get_dorm_weekly_meal` tool 보임
- [ ] `search_campus_facilities` tool 보임

---

## 8. Oracle이 계속 안 될 때 fallback: Render

Oracle VM이 하루 이상 안 잡히면 Render로 backend를 먼저 공개할 수 있습니다.

### 8.1 Render backend 만들기

만드는 방법:

1. <https://render.com> 접속
2. GitHub 로그인
3. `New` → `Web Service`
4. ssuMCP repo 선택
5. Root Directory:

```text
.
```

6. Build Command:

```bash
chmod +x gradlew && ./gradlew bootJar -x test
```

7. Start Command:

```bash
java -Dserver.port=$PORT $JAVA_OPTS -jar build/libs/*-SNAPSHOT.jar
```

8. Environment Variables:

```text
SPRING_PROFILES_ACTIVE=prod
SSUAI_FRONTEND_ORIGIN=<VERCEL_URL>
SSUAI_CONNECTOR_MEAL=real
SSUAI_CONNECTOR_DORM_MEAL=real
JAVA_OPTS=-Xmx384m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError
```

Render backend URL 예시:

```text
https://ssumcp.onrender.com
```

Render MCP endpoint:

```text
https://ssumcp.onrender.com/mcp
```

체크:

- [ ] Render Web Service 생성
- [ ] backend health 확인
- [ ] Vercel의 `NEXT_PUBLIC_SSUAI_API_BASE`를 Render URL로 변경
- [ ] Render의 `SSUAI_FRONTEND_ORIGIN`을 Vercel URL로 설정

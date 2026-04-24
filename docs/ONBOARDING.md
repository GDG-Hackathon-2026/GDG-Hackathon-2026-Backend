# 새 로컬 환경에서 작업 이어가기 (Onboarding)

> 컴퓨터를 바꾸거나 새 팀원이 합류했을 때 이 문서 하나만 따라 하면 개발·배포 가능하도록.
> 배포는 `main` push 로 자동화되어 있어 로컬에서 할 일은 코드 수정 + git push 뿐.

---

## 0. 갖춰야 할 것 (체크리스트)

- [ ] Git
- [ ] Java 21 (Temurin 권장) — 로컬 빌드·테스트용
- [ ] (선택) Docker — 로컬에서 이미지 빌드·실행하려면
- [ ] SSH 클라이언트 (Windows: OpenSSH 내장 or git-bash)
- [ ] **`2026_GDG.pem` 파일** — EC2 SSH 전용 키. 팀원(나지성)에게 개별 요청해서 받고 `~/.ssh/` 아래 둔다. git 에 절대 커밋 금지.

---

## 1. 저장소 clone

```bash
git clone https://github.com/GDG-Hackathon-2026/GDG-Hackathon-2026-Backend.git
cd GDG-Hackathon-2026-Backend
```

가장 먼저 [CLAUDE.md](../CLAUDE.md) 읽을 것. 프로젝트 전체 맥락 + 미완 작업 정리됨.

---

## 2. SSH 설정 (EC2 접속용)

### 2-A. 키 파일 배치
```
<홈>/.ssh/2026_GDG.pem  (권한 600)
```
Windows 라면 `C:\Users\<you>\.ssh\2026_GDG.pem`. 파일 받은 뒤:
```bash
# Linux/Mac
chmod 600 ~/.ssh/2026_GDG.pem

# Windows (PowerShell) — 권한 단순화
icacls "$HOME\.ssh\2026_GDG.pem" /inheritance:r /grant:r "$($env:USERNAME):(R)"
```

### 2-B. `~/.ssh/config` 에 alias 추가
```
Host Lion-2026gdg
  HostName 3.39.235.46
  User ec2-user
  Port 22
  IdentityFile ~/.ssh/2026_GDG.pem
```
Windows 는 `IdentityFile C:\Users\<you>\.ssh\2026_GDG.pem` 로.

> **EC2 인스턴스가 재생성되면 IP 가 바뀐다.** 이 경우 HostName 업데이트 필요. Elastic IP 를 아직 안 쓰고 있음.

### 2-C. 테스트
```bash
ssh Lion-2026gdg 'docker ps --format "{{.Names}}"'
# gdg-app, gdg-mysql, gdg-prometheus, gdg-grafana 가 보여야 정상
```

---

## 3. 로컬 빌드 · 테스트

Gradle wrapper 포함이므로 JDK 21 만 있으면 됨:
```bash
./gradlew bootJar      # build/libs/*.jar 생성
./gradlew test         # H2 in-memory 로 JPA 컨텍스트 검증
```

로컬에서 Spring Boot 를 직접 실행할 일은 거의 없음 (MySQL/Gemini/Firebase 필요해서). 필요하면:
- `SPRING_PROFILES_ACTIVE=local` (기본)
- JPA datasource 없어도 H2 autoconfig 가 있어서 컨텍스트 로드만 됨 (실제 엔드포인트는 DB 필요)

---

## 4. GCP / Firebase / Docker Hub (로컬에서 건드릴 일 거의 없음)

- **Vertex AI SA key, Firebase Admin SDK key 는 EC2 `~/app/` 에만 있다.** 로컬에 둘 필요 없음.
- **Docker Hub**: 로컬에서 이미지 빌드·푸시할 일 있으면 `docker login` (계정 `jiseong02`).
- **GitHub Secrets** (백엔드 리포): `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`. 로컬에선 건드리지 않음.

> 새 팀원이 GCP 콘솔 접근 필요하면 기존 멤버 중 Owner 권한자가 Firebase 콘솔 + GCP IAM 에 초대 추가. 이메일만 넘기면 됨.

---

## 5. 개발 루프

```
코드 수정 → ./gradlew test → git push origin main
  → GitHub Actions 가 5~7분 후 자동 배포
  → 새 컨테이너 기동 (docker ps 에서 StartedAt 바뀜)
  → Swagger UI 로 바로 테스트
```

배포 끝났는지 확인:
```bash
ssh Lion-2026gdg 'docker inspect -f "{{.State.StartedAt}}" gdg-app'
# 최근 시간이면 새 이미지로 돌고 있는 것
```

---

## 6. 인증 없이 빠른 테스트 (DevAuth)

해커톤 기간엔 `DEV_AUTH_BYPASS=true` 로 EC2 가 기동 중. Firebase 토큰 없이도:

```bash
# 대화 생성
curl -X POST http://3.39.235.46:8080/api/conversations \
  -H "Content-Type: application/json" \
  -H "X-Dev-Uid: whoever-i-am" \
  -d '{"title":"test"}'

# 메시지 (Gemini 실제 호출)
curl -X POST "http://3.39.235.46:8080/api/conversations/<id>/messages" \
  -H "Content-Type: application/json" \
  -H "X-Dev-Uid: whoever-i-am" \
  -d '{"content":"hello"}'
```

Swagger UI 에서도 우상단 **Authorize** → `devUid` 칸에 임의 값 입력하면 모든 요청에 자동 첨부.

> `X-Dev-Uid` 의 값은 자유 문자열. 그 값이 users 테이블의 uid 로 쓰이고, 탄소 누적 소유자가 됨. 같은 값으로 여러 번 호출하면 누적이 유지됨.

---

## 7. EC2 직접 손봐야 할 경우

대부분 경우 git push 로 끝나지만 다음 상황에선 SSH 로 직접:

| 상황 | 명령 |
|------|------|
| compose 스택 수동 재기동 | `ssh Lion-2026gdg 'cd ~/app && docker compose up -d'` |
| 특정 서비스만 재기동 | `ssh Lion-2026gdg 'cd ~/app && docker compose up -d --no-deps app'` |
| `.env` 값 변경 | `ssh Lion-2026gdg 'nano ~/app/.env'` 후 `docker compose up -d` |
| SA key 교체 | `scp new-sa.json Lion-2026gdg:~/app/gcp-sa.json && ssh Lion-2026gdg 'cd ~/app && docker compose restart app'` |
| DB 쿼리 | `ssh Lion-2026gdg 'docker exec -it gdg-mysql mysql -u gdg -p1234 gdg'` |
| 로그 확인 | `ssh Lion-2026gdg 'docker logs gdg-app --tail 100'` |

---

## 8. 트러블 · 자주 겪는 문제

- **SSH 접속 안 됨**: 보안그룹 인바운드 22 가 "내 IP" 로만 열려있을 수 있음. 새 네트워크(집/카페)면 AWS 콘솔에서 IP 업데이트. 또는 `0.0.0.0/0` 임시 허용 (권장 X).
- **key 권한 에러**: `Unprotected private key file` → chmod 600 또는 Windows 권한 재설정 (위 2-A 참조).
- **EC2 인스턴스 IP 바뀜**: 정지/재시작하면 바뀜. AWS 콘솔에서 새 IP 확인 → `~/.ssh/config` 의 HostName + 필요시 `/src/main/java/com/moggo/_gdg/config/CorsConfig.java` 의 origin, `OpenApiConfig` servers 도 반영해야.
- **Firebase 토큰이 401 로 거부됨**: 프로젝트 aud mismatch. 프론트 config 가 `gdg-hackathon-494307` 인지 확인.
- **자세한 함정은** [CLAUDE.md § 9](../CLAUDE.md#9-자주-밟는-함정).

---

## 9. 세션 재개시 루틴 (다시 시작할 때 복붙)

```bash
# 건강 확인
curl -sf http://3.39.235.46:8080/actuator/health
ssh Lion-2026gdg 'docker ps --format "table {{.Names}}\t{{.Status}}"'

# 내 상태 (DevAuth)
curl http://3.39.235.46:8080/api/me -H "X-Dev-Uid: jiseong-local"

# 기본 메시지 전송 테스트 (Gemini + 북극곰 페르소나)
CID=$(curl -s -X POST http://3.39.235.46:8080/api/conversations \
  -H "Content-Type: application/json" -H "X-Dev-Uid: jiseong-local" \
  -d '{"title":"restart test"}' | python -c "import sys,json; print(json.load(sys.stdin)['id'])")
curl -X POST "http://3.39.235.46:8080/api/conversations/$CID/messages" \
  -H "Content-Type: application/json" -H "X-Dev-Uid: jiseong-local" \
  -d '{"content":"hi polar bear"}'
```

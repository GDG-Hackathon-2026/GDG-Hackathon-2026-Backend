# 프론트엔드 CI/CD 구축 가이드 (v2: 분리 구조)

> **읽는 사람**: GDG-Hackathon-2026-Front 리포에서 작업하는 Claude Code 세션 또는 팀원.
> **이전 버전 차이**: v1은 백엔드 compose에 프론트 서비스를 합치는 "통합 compose" 전략이었으나, 오너십이 꼬여 폐기됨. 이 v2는 **두 리포가 각자 compose를 갖고 Docker 외부 네트워크 `shared` 로만 연결하는 분리 구조**.

---

## 1. 이미 구축된 인프라 (재사용 자산)

- **AWS EC2 (t3.small, 2GB RAM, Amazon Linux 2023)**
  - SSH alias `Lion-2026gdg` (퍼블릭 IP `3.39.235.46`)
  - Docker 25.x + Docker Compose v2 plugin 설치됨, Swap 2GB 설정됨
  - **외부 Docker 네트워크 `shared` 이미 생성됨** (백엔드 세션이 사전 세팅)
- **백엔드 스택** — EC2 경로 `~/app/`, compose 파일은 백엔드 리포가 단일 오너
  - 서비스: `gdg-app`(Spring Boot :8080) / `gdg-mysql` / `gdg-prometheus`(:9090) / `gdg-grafana`(:3000)
  - 네트워크: 모든 서비스 `shared` 에 attach
  - **CORS 허용 origin**: `http://3.39.235.46:3001`, `http://localhost:3000`, `http://localhost:3001` (백엔드 `CorsConfig.java` 에 설정됨)
- **Docker Hub**
  - 백엔드 이미지: `jiseong02/gdg-2026-backend`
  - 프론트 이미지: `jiseong02/gdg-2026-frontend` (이미 `latest` + sha 태그 존재)
- **보안 그룹 인바운드**: 22, 8080, 3000, 9090, **3001** (모두 설정됨)

---

## 2. 분리 구조의 핵심 규칙

- **프론트 리포는 자기 `docker-compose.yml` 을 가진다** — 프론트 리포 루트에 커밋, EC2 `~/app-web/` 경로로 배포
- **두 스택은 Docker 외부 네트워크 `shared` 로만 연결** — 백엔드 컨테이너 이름(`gdg-app`, `gdg-mysql` 등)으로 직접 통신 가능
- **프론트 배포가 백엔드 스택을 건드리지 않는다** — `docker compose up -d` 가 `~/app-web/` 디렉토리에서만 동작하므로 `~/app/` 백엔드 스택과 완전 독립
- **compose 파일 scp 대상 경로를 반드시 `~/app-web/` 로** — 백엔드 `~/app/` 에 덮어쓰면 사고

---

## 3. 프론트 리포에 들어갈 파일

### 3-A. `docker-compose.yml` (프론트 리포 루트에 신규)

```yaml
services:
  web:
    image: ${DOCKERHUB_USERNAME}/gdg-2026-frontend:latest
    container_name: gdg-web
    restart: unless-stopped
    ports:
      - "3001:3000"
    environment:
      NODE_ENV: production
    networks:
      - shared

networks:
  shared:
    name: shared
    external: true
```

### 3-B. `.env.example`

```
DOCKERHUB_USERNAME=jiseong02
```

EC2 `~/app-web/.env` 파일을 직접 생성 (아래 3-D 참조). 커밋 금지.

### 3-C. `.github/workflows/deploy.yml` — 분리 구조 버전

기존 프론트 workflow의 `deploy` job 을 다음으로 교체:

```yaml
  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Copy compose to EC2
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          source: "docker-compose.yml"
          target: "/home/ec2-user/app-web"

      - name: SSH & redeploy
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd /home/ec2-user/app-web
            if [ ! -f .env ]; then
              echo "ERROR: /home/ec2-user/app-web/.env not found. Create it before first deploy." >&2
              exit 1
            fi
            docker compose pull
            docker compose up -d --remove-orphans
            docker image prune -f
            docker compose ps
```

변경점 요약:
- 과거: `docker compose pull web && up -d --no-deps web` (백엔드 compose 재사용)
- 현재: `docker compose pull && up -d` (프론트 전용 compose, 서비스 1개)

### 3-D. EC2 초기 세팅 (최초 1회만, 프론트 배포 전)

```bash
ssh Lion-2026gdg 'mkdir -p ~/app-web && cat > ~/app-web/.env <<EOF
DOCKERHUB_USERNAME=jiseong02
EOF
chmod 600 ~/app-web/.env'
```

**이 단계 안 하면** 프론트 첫 배포 시 workflow 의 가드("ERROR: .env not found")에 막혀 실패.

---

## 4. 작업 순서 체크리스트

차기 세션이 순서대로 진행:

- [ ] EC2 `~/app-web/` 디렉토리 + `.env` 생성 (3-D)
- [ ] 프론트 리포 루트에 `docker-compose.yml` 추가 (3-A)
- [ ] 프론트 리포의 `.github/workflows/deploy.yml` 의 `deploy` job 교체 (3-C)
- [ ] 프론트 리포 main 에 push → Actions 성공 확인
- [ ] EC2 에서 `docker ps | grep gdg-web` 으로 컨테이너 기동 확인
- [ ] 브라우저 `http://3.39.235.46:3001` → 페이지 렌더링 + DevTools 에서 API 호출 200

## 5. 검증

```bash
# 백엔드 스택 무변동 확인 (여전히 4개 컨테이너)
ssh Lion-2026gdg 'docker compose -f ~/app/docker-compose.yml ps'

# 프론트 스택 독립 기동 확인
ssh Lion-2026gdg 'docker compose -f ~/app-web/docker-compose.yml ps'
# gdg-web | Up | 0.0.0.0:3001->3000/tcp

# 전체 컨테이너 5개 확인
ssh Lion-2026gdg 'docker ps --format "{{.Names}}"'

# CORS preflight
curl -i -X OPTIONS http://3.39.235.46:8080/api/anything \
  -H "Origin: http://3.39.235.46:3001" \
  -H "Access-Control-Request-Method: GET"
# → Access-Control-Allow-Origin: http://3.39.235.46:3001

# 프론트 HTTP
curl -I http://3.39.235.46:3001
# → HTTP/1.1 200 OK
```

## 6. 프론트에서 백엔드 호출

- **브라우저 (클라이언트 컴포넌트)**: `http://3.39.235.46:8080` 직접 (CORS 허용됨)
- **SSR / Server Component / Route Handler**: 같은 Docker 네트워크라 `http://gdg-app:8080` 도 가능. 다만 빌드 타임에 `NEXT_PUBLIC_API_URL=http://3.39.235.46:8080` 로 번들에 박혔으므로 그대로 외부 IP 써도 무방 (SSR 시 EC2 내부에서 EC2 외부 IP로 왔다가는 것뿐, 네트워크 비용은 AWS 내부 처리)
- 비용/지연 최적화 원하면 서버 사이드 전용으로 `INTERNAL_API_URL=http://gdg-app:8080` 을 추가 env 로 주입해 분기

## 7. 자주 밟는 함정

| 함정 | 증상 | 해결 |
|------|------|------|
| `.env` 파일 누락 | 첫 배포 실패, "ERROR: .env not found" | 3-D 사전 실행 |
| compose 파일을 `~/app/` 로 scp | 백엔드 스택 덮어쓰기 → 백엔드 재기동 + 서비스 혼란 | target 을 반드시 `/home/ec2-user/app-web` |
| `shared` 네트워크 미존재 | `up -d` 가 "network shared not found" 로 실패 | 백엔드가 이미 만들었어야 정상. 없다면 `docker network create shared` |
| `NEXT_PUBLIC_*` 누락 | 브라우저에서 API URL undefined | docker build-args 로 빌드 타임 주입 (프론트 Dockerfile 에 `ARG NEXT_PUBLIC_API_URL` + `ENV`) |
| CORS 에러 | `Access-Control-Allow-Origin` 없음 | 백엔드 `CorsConfig.java` 에 해당 origin 추가 후 재배포 (백엔드 리포 PR 필요) |
| 포트 3000 충돌 | Grafana 와 프론트 동시 호스트 3000 바인딩 실패 | 프론트는 반드시 `3001:3000` |
| Docker Hub rate limit | `toomanyrequests` | EC2 에서 `docker login -u jiseong02` 1회 |
| depends_on 불가 | 백엔드 서비스를 depends_on 할 수 없음 (다른 compose project) | 네트워크로 느슨히 연결만. 앱 기동 순서가 필요하면 런타임 retry 로직으로 해결 |

---

## 8. 해커톤 이후 정돈 제안

- Nginx reverse proxy 도입 → `:80` 단일 진입점, CORS 불필요
- 도메인 + HTTPS (Let's Encrypt / ACM)
- Grafana/Prometheus provisioning 코드화
- `ddl-auto=update` → Flyway 마이그레이션

---

## 9. 다음 Claude 세션에게

- 이 가이드 + 백엔드 리포 `docker-compose.yml`, `src/main/java/com/moggo/_gdg/config/CorsConfig.java` 를 먼저 읽으면 구조가 빠르게 이해됨
- EC2 상태 확인: `ssh Lion-2026gdg 'docker ps && docker network inspect shared'`
- 배포 실패 시 항상 `docker compose logs` 부터 — GitHub Actions 의 SSH step 로그보다 원격 로그가 진실

# 프론트엔드 CI/CD 구축 가이드

> **읽는 사람**: GDG-Hackathon-2026-Front 리포에서 작업하는 차기 Claude Code 세션 또는 팀원.
> 백엔드 리포에서 이미 구축한 인프라 위에 프론트를 얹는 작업을 이어받기 위해 필요한 컨텍스트를 모두 담았다.

---

## 1. 이미 구축된 인프라 (재사용 자산)

- **AWS EC2 (t3.small, 2GB RAM, Amazon Linux 2023)**
  - SSH: `~/.ssh/config` 에 `Host Lion-2026gdg` alias (퍼블릭 IP `3.39.235.46`)
  - 키: `C:\Users\pupaj\.ssh\2026_GDG.pem` (같은 키를 프론트 리포 `EC2_SSH_KEY` Secret에도 등록)
  - Swap 2GB 설정됨, Docker 25.x + Docker Compose v2 plugin 설치됨
  - `~/app/` 이 배포 루트. `~/app/.env` 에 공통 시크릿 보관 (권한 600)
- **실행 중인 컨테이너** (`docker compose` 단일 스택)
  - `gdg-app` : Spring Boot API (`:8080`)
  - `gdg-mysql` : MySQL 8 (내부 네트워크 전용, 외부 미노출)
  - `gdg-prometheus` : 메트릭 스크랩 (`:9090`)
  - `gdg-grafana` : 대시보드 (`:3000`)
  - 네트워크명 : `app_backend` (compose 프로젝트명 `app` + network `backend`)
- **Docker Hub 계정**: `jiseong02`
  - 백엔드 이미지: `jiseong02/gdg-2026-backend`
  - 프론트 이미지 권장 이름: `jiseong02/gdg-2026-frontend`
- **보안 그룹 인바운드**: 22(내 IP), 8080(0.0.0.0/0), 9090(0.0.0.0/0), 3000(0.0.0.0/0)
  - 프론트용으로 **80 또는 3001 추가 필요** (Next.js 기본 3000과 Grafana 3000 충돌 주의)
- **GitHub Actions 패턴**: 백엔드 리포의 `.github/workflows/deploy.yml` 이 레퍼런스. Docker Hub push → SSH(appleboy/ssh-action) → `docker compose pull && up -d`
- **핵심 결정 히스토리**
  - MySQL은 RDS 아닌 EC2 컨테이너 (해커톤 비용/단순성)
  - 모니터링은 데모 편의로 0.0.0.0/0 공개 (해커톤 종료 후 강화)
  - `ddl-auto=update` (해커톤 한정, 이후 Flyway 이관 예정)

---

## 2. 프론트엔드 스택 & 채택 전략

- **스택**: Next.js + TypeScript + App Router (`app/`), npm, CSS
- **배포 위치**: 백엔드와 동일 EC2, **같은 docker-compose 스택에 서비스 추가**

### 두 리포 간 분업 규칙 (중요)

`~/app/docker-compose.yml` 은 **백엔드 리포가 단일 오너**. 프론트 서비스 정의도 백엔드 리포 compose에 포함시킨다. 이렇게 하는 이유:

- 두 리포가 각자 compose 파일을 EC2로 scp 하면 서로 덮어써서 사고가 난다
- 프론트 CI는 **이미지 빌드·푸시까지만** 책임지고, 배포 단계는 `docker compose pull web && up -d --no-deps web` 로 자기 서비스만 재시작

즉 프론트 리포에서 **가장 먼저 해야 할 일은 백엔드 리포에 프론트 서비스 정의를 추가하는 PR** 이다. (아래 3-B 템플릿 참조)

---

## 3. 구체적 작업 단계

### 3-A. 프론트 리포에 추가할 파일

#### `Dockerfile` (Next.js standalone output 기준 — 이미지 크기 최소화)

```dockerfile
# ---- deps ----
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci

# ---- build ----
FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- runtime ----
FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
RUN addgroup -S nodejs -g 1001 && adduser -S nextjs -u 1001 -G nodejs
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
```

**`next.config.ts` 에 `output: 'standalone'` 가 반드시 있어야 함** — 없으면 `/app/.next/standalone` 이 생성되지 않아 빌드 실패.

#### `.dockerignore`
```
node_modules
.next
.git
.github
.env*
!.env.example
README.md
*.md
```

#### `.env.example`
```
NEXT_PUBLIC_API_BASE_URL=http://3.39.235.46:8080
```

로컬 개발과 배포에서 다른 값이 필요할 수 있다. 초기에는 브라우저가 직접 `3.39.235.46:8080` 을 때리는 구조 → **백엔드에 CORS 허용 설정 추가 필요** (프론트 오리진 `http://3.39.235.46:3001` 혹은 배포 도메인). 이건 백엔드 리포 수정 사항.

#### `.github/workflows/deploy.yml`

```yaml
name: Build & Deploy Frontend

on:
  push:
    branches: [main]
  workflow_dispatch:

env:
  IMAGE_NAME: ${{ secrets.DOCKERHUB_USERNAME }}/gdg-2026-frontend

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
      - run: npm ci
      - run: npm run lint --if-present
      - run: npm run build
        env:
          NEXT_PUBLIC_API_BASE_URL: http://3.39.235.46:8080
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}
      - uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: |
            ${{ env.IMAGE_NAME }}:latest
            ${{ env.IMAGE_NAME }}:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
          build-args: |
            NEXT_PUBLIC_API_BASE_URL=http://3.39.235.46:8080

  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USER }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd /home/ec2-user/app
            docker compose pull web
            docker compose up -d --no-deps web
            docker image prune -f
            docker compose ps
```

**주의**: `NEXT_PUBLIC_*` 환경변수는 **빌드 타임에 번들에 박히므로** Docker 빌드 시점에 전달해야 한다 (`build-args` 와 Dockerfile `ARG NEXT_PUBLIC_API_BASE_URL` + `ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL` 필요). 위 Dockerfile은 빌드 타임에만 필요한 간단한 케이스라 생략됐는데, 빌드 과정에서 이 값을 참조하려면 Dockerfile에 `ARG` 두 줄 추가:

```dockerfile
# builder stage에 추가
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
```

### 3-B. 백엔드 리포에 내야 할 PR (프론트 리포 작업 **전에** 먼저)

`docker-compose.yml` 의 `services:` 블록 끝에 추가:

```yaml
  web:
    image: ${DOCKERHUB_USERNAME}/gdg-2026-frontend:latest
    container_name: gdg-web
    restart: unless-stopped
    ports:
      - "3001:3000"   # Grafana 3000과 충돌 회피
    environment:
      NODE_ENV: production
    depends_on:
      - app
    networks:
      - backend
```

그리고 `.env.example` 에 변경 없음 (DOCKERHUB_USERNAME 재사용).

이 PR이 main 에 머지되면 백엔드 deploy.yml 이 발동하면서 compose 파일을 EC2로 scp하고 `pull` 하지만, 이 시점엔 아직 `gdg-2026-frontend:latest` 이미지가 Docker Hub 에 없으면 pull 실패로 `up -d` 가 죽는다. **순서 주의**:

1. 프론트 리포에서 먼저 Dockerfile만 추가 + workflow 수동 실행(`workflow_dispatch`)으로 `latest` 이미지 한 번 Docker Hub에 업로드
2. 백엔드 리포에서 compose 에 `web` 서비스 추가 PR → 머지 → 배포 성공
3. 이후로는 각 리포가 독립적으로 자기 서비스만 재배포

### 3-C. GitHub Secrets (프론트 리포 Settings → Secrets and variables → Actions)

같은 값을 프론트 리포에도 따로 등록한다:

| Secret | 값 |
|--------|-----|
| `DOCKERHUB_USERNAME` | `jiseong02` |
| `DOCKERHUB_TOKEN` | 백엔드 리포와 동일한 Access Token 재사용 가능 (또는 새로 발급) |
| `EC2_HOST` | `3.39.235.46` |
| `EC2_USER` | `ec2-user` |
| `EC2_SSH_KEY` | `2026_GDG.pem` 파일 전체 내용 |

### 3-D. 보안 그룹 추가 (AWS 콘솔 → EC2 → 보안 그룹)

| Port | Source |
|------|--------|
| 3001 | 0.0.0.0/0 |

---

## 4. 백엔드 CORS 설정 (따로 필요)

프론트 브라우저가 `http://3.39.235.46:3001` 에서 `http://3.39.235.46:8080/api/...` 를 호출하므로 Spring Boot에 CORS 허용이 필요. 백엔드 리포에 다음 구성 클래스를 추가하는 PR.

```java
// com.moggo._gdg.config.CorsConfig
package com.moggo._gdg.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {
    @Bean
    WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMapping(CorsRegistry registry) {
                registry.addMapping("/**")
                    .allowedOriginPatterns(
                        "http://3.39.235.46:3001",
                        "http://localhost:3000",
                        "http://localhost:3001"
                    )
                    .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
                    .allowCredentials(true);
            }
        };
    }
}
```

도메인 붙이면 해당 origin 도 추가.

---

## 5. 메모리 예산 체크

t3.small = 2GB. 추가 Next.js 서버는 idle 200~300MB, 트래픽 타면 500MB 가능. 예상 총합:

| 서비스 | 예상 메모리 |
|--------|-----------|
| Spring Boot (MaxRAMPercentage 60 + SerialGC) | ~500MB |
| MySQL | ~400MB |
| Prometheus | ~150MB |
| Grafana | ~200MB |
| Next.js | ~300~500MB |
| **합계** | ~1.5~1.75GB |

여유는 있으나 빡빡. 프론트 배포 후 `free -h` 로 swap 사용량 모니터링 권장. 스왑 자주 쓰면 t3.medium(4GB)으로 스케일업 고려.

---

## 6. 전체 검증 체크리스트

프론트 첫 배포 후 다음이 전부 통과해야 한다:

```bash
# 1. 컨테이너 기동
ssh Lion-2026gdg 'docker compose -f ~/app/docker-compose.yml ps'
#   gdg-web | Up | 0.0.0.0:3001->3000/tcp  (건강 상태)

# 2. 프론트 직접 응답
curl -I http://3.39.235.46:3001
#   HTTP/1.1 200 OK

# 3. 브라우저에서 http://3.39.235.46:3001 접속 → 페이지 렌더링, 네트워크 탭에서 백엔드 호출 200 (CORS 에러 없음)

# 4. CI/CD 루프: 프론트 리포 main 에 사소한 커밋 push → Actions 녹색 → 3분 내 배포 반영
```

---

## 7. 자주 밟는 함정

| 함정 | 증상 | 해결 |
|------|------|------|
| `output: 'standalone'` 누락 | Docker 빌드 시 `.next/standalone` 이 없다며 실패 | `next.config.ts` 수정 후 커밋 |
| `NEXT_PUBLIC_*` 환경변수가 `undefined` | 브라우저 콘솔 `API URL is undefined` | 빌드 타임에 `--build-arg` 로 주입 필수, 런타임 env 주입만으로는 번들에 박히지 않음 |
| CORS 에러 | `Access-Control-Allow-Origin` 헤더 없음 | 백엔드에 CorsConfig 추가 (4번 섹션) |
| 포트 3000 충돌 | Grafana 3000 과 Next.js 3000 이 같이 0.0.0.0 에 바인드하려다 실패 | 위처럼 `3001:3000` 매핑 |
| compose 파일 덮어쓰기 | 프론트 리포가 자체 compose.yml 을 scp 하면 백엔드 스택이 망가짐 | 프론트 CI 는 이미지 푸시 + `docker compose pull web && up -d --no-deps web` 만 실행. compose 파일 전송 금지 |
| Docker Hub rate limit | `pull` 이 `toomanyrequests` 로 실패 | EC2에서 `docker login -u jiseong02` 한 번 실행 (자격 `~/.docker/config.json` 에 저장) |
| EC2 스왑 폭주 | 응답 느려짐, `vmstat` 에 si/so 높음 | t3.medium 스케일업 또는 Grafana/Prometheus 일시 중단 |

---

## 8. 작업 순서 요약 (체크리스트)

차기 세션이 이 가이드를 보고 순서대로 진행하면 된다:

- [ ] 프론트 리포에 `Dockerfile`, `.dockerignore`, `.env.example` 추가
- [ ] `next.config.ts` 에 `output: 'standalone'` 반영
- [ ] `.github/workflows/deploy.yml` 작성 (위 템플릿)
- [ ] 프론트 리포 GitHub Secrets 5개 등록 (3-C)
- [ ] Actions workflow `workflow_dispatch` 로 수동 실행 → `jiseong02/gdg-2026-frontend:latest` 이미지가 Docker Hub 에 올라가는지 확인
- [ ] 백엔드 리포에 `docker-compose.yml` `web` 서비스 추가 PR + CORS 설정 PR (둘 다)
- [ ] 보안 그룹에 3001 오픈
- [ ] 두 PR 머지 → 배포 트리거 → 6번 검증 체크리스트 모두 통과
- [ ] `git push` 만으로 반영되는지 프론트 리포에서 실제 커밋 한 번으로 확인

---

## 9. 다음 단계 (해커톤 이후 권장)

이 가이드 범위는 "최소한으로 돌아가게" 만드는 것. 다음은 안정화·팀 확장을 위한 개선 방향:

- **Nginx reverse proxy 도입**: `:80` 단일 진입점으로 프론트 + `/api/*` 백엔드 프록시. CORS 불필요, HTTPS 도입 용이
- **도메인 + HTTPS**: Route53/가비아 도메인 + Let's Encrypt (Caddy 쓰면 더 간편)
- **Grafana/Prometheus provisioning**: `grafana/provisioning/datasources/` 와 `dashboards/` 를 compose 볼륨으로 마운트. 현재 수동 설정이 볼륨 삭제 시 사라짐
- **Spring Boot Flyway**: `ddl-auto=update` 제거, 마이그레이션 스크립트 관리
- **MySQL 백업**: cronjob 으로 `mysqldump` → S3 업로드

---

## 10. 다음 Claude 세션에게

이 가이드만으로 충분히 진행 가능하도록 작성했지만, 막히면:

- **백엔드 리포 `.github/workflows/deploy.yml`** 이 가장 정확한 레퍼런스
- **백엔드 리포 `docker-compose.yml`** 을 그대로 열어 현재 compose 구조 확인
- EC2 상태는 `ssh Lion-2026gdg 'docker compose -f ~/app/docker-compose.yml ps'` 로 항상 확인 가능
- Grafana 설정 관련 이력(데이터 소스 이름을 `prometheus` → `Prometheus` 대문자로 변경했음, 대시보드 JSON의 `${DS_PROMETHEUS}` 치환 이슈 있음)은 이 가이드 9번 provisioning 항목 참고

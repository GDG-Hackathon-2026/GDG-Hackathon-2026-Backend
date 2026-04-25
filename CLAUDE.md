# Polar.ai 백엔드 — 세션 이어서 작업할 때 읽는 브리핑

> **다음 세션 Claude / 다른 환경에서 이어 작업할 개발자가 가장 먼저 읽을 문서.**
> 새 로컬 환경 셋업 절차는 [docs/ONBOARDING.md](docs/ONBOARDING.md) 참조.

## 1. 프로젝트 개요 (30초)

- **이름**: Polar.ai — *"당신의 UI/UX가 서서히 녹아내린다"*
- **팀**: Moggo (김형준 프론트, 나지성 백엔드/인프라)
- **컨셉**: AI 채팅할수록 탄소 누적 → UI 녹아내리고 컨텍스트 창이 축소됨. 사용자가 사라져가는 빙하와 자기 사용의 비용을 동시에 체감.
- **LLM 페르소나**: 북극곰 "포라(Pora)" — **"아무 쓸모가 없어야 하는" AI**. 사용자 질문은 자기 추억·풍경·푸념을 꺼내기 위한 소재로만 쓰이고, 실제 답변·정보·조언·코드·수치는 **단 한 글자도 주지 않는** 것이 설계 목표. 정보 **0%, 헛소리 100%**.
- **기획 원문**: [docs/PLAN.md](docs/PLAN.md)

## 2. 아키텍처 현황

```
[브라우저 (Next.js)]
  Firebase Auth (signInAnonymously) → ID token
      │
      ▼  Authorization: Bearer <token>  (또는 dev 전용 X-Dev-Uid)
[EC2 t3.small · Amazon Linux 2023 · 13.125.197.228]
  docker 외부 네트워크 `shared`
  ├─ gdg-app       Spring Boot 4.0.5 / Java 21 / 8080 (이 리포)
  ├─ gdg-mysql     MySQL 8 / 내부 전용
  ├─ gdg-prometheus  :9090
  └─ gdg-grafana   :3000
  (프론트 컨테이너 gdg-web 은 프론트 리포가 독립 compose 로 관리 — ~/app-web)
```

- 외부 네트워크 분리 구조 결정: 프론트/백엔드 리포가 각자 compose 소유. 공용 네트워크명 `shared`.
- SA key 2개가 EC2 `~/app/` 에 직접 배포됨 (git 미포함): `gcp-sa.json` (Vertex AI), `firebase-sa.json` (Firebase Admin SDK).
- MySQL 은 EC2 docker 볼륨 `mysql_data`. 해커톤 기간 한정 — 인스턴스 재생성 시 데이터 소실.

## 3. 주요 엔드포인트 (Swagger 참조)

- http://13.125.197.228:8080/swagger-ui.html — 우상단 **Authorize** 버튼으로 Bearer 또는 X-Dev-Uid 입력
- `POST /api/conversations` — 대화 생성
- `GET /api/conversations` — 내 대화 목록
- `GET /api/conversations/{id}` — 대화 + 전체 메시지
- `POST /api/conversations/{id}/messages` — Gemini 호출 + 탄소 누적 + 녹아내림 단계 반영
- `GET /api/me` — 내 uid / 누적 탄소 / stage / maxInputTokens / meltingPercent
- `POST /api/me/carbon/reset` — 내 누적 탄소 0 으로 초기화, 갱신된 MeResponse 반환
- `GET /api/stats/carbon` — 전체 사용자 탄소 통계 (userCount·activeUserCount·총합·전체 평균·활성 평균). 개인 식별 정보 없음. 인증 필요
- `POST /api/gemini/generate` — raw Gemini (탄소/녹아내림 없음). body 의 `systemPrompt` 로 override 가능, 없으면 현재 active 템플릿 자동 적용. `GEMINI_RAW_PUBLIC=true` 면 인증 없이 호출 가능 (프롬프트 엔지니어링 반복용)

## 4. 인증 · 환경변수 게이트

| 경로 | 의미 |
|------|------|
| `Authorization: Bearer <Firebase ID token>` | 프로덕션 경로. FirebaseTokenFilter 가 verifyIdToken 후 uid principal 세팅 |
| `X-Dev-Uid: <임의>` | **dev 전용 우회**. `DEV_AUTH_BYPASS=true` 일 때만 필터 빈 등록 + OpenAPI 노출. 지금 EC2 에선 활성. 해커톤 이후 반드시 false. |
| `GEMINI_RAW_PUBLIC=true` | **프롬프트 엔지니어링 전용 공개**. `POST /api/gemini/generate` 만 인증 없이 호출 가능. 빌링 남용 위험 — 반복 실험 끝나면 반드시 false. |

기본 `/api/ping` 외 전부 인증 필수 (`GEMINI_RAW_PUBLIC=true` 면 `POST /api/gemini/generate` 도 예외). 인증 실패는 `ErrorResponse` JSON 포맷 (`code=AUTH`).

## 5. 탄소 · 녹아내림 정책 (`CarbonPolicy`)

- 토큰→탄소 환산 상수 `carbon.g-per-1k-input-tokens` (0.015), `g-per-1k-output-tokens` (0.025) — Google 2025 Gemini 2.5 Flash 기반 근사. **논문 기반 수치로 교체 예정.**
- Stage 별 `maxInputTokens`: 0→8192, 1→4096, 2→2048, 3→1024, 4→512, 5→요청 거부(402 `code=MELTED`)
- 출력 토큰은 stage 와 **독립**해 `gemini.max-output-tokens=8192` 고정 → "기억은 흐릿해도 수다는 끝없다"

## 6. ChatService 핵심 동작

1. 사용자 메시지 원본 저장
2. 지금까지의 모든 메시지를 role 구분 (user/model) 한 `Content[]` 로 변환
3. 예산(stage.maxInputTokens × 4 chars 근사) 안에 들어올 때까지 **오래된 것부터 drop**. 마지막 user 턴은 필수.
4. `generateWithHistory(contents, systemPrompt)` 호출 — Vertex AI Gemini 2.5 Flash
5. usage 기반 탄소 누적 → 사용자 row 업데이트
6. assistant 메시지 저장 + 새 CarbonState 반환

## 7. 시스템 프롬프트

- `src/main/resources/prompts/*.md` 에 템플릿 저장. `chat.prompt.active-template` (기본 `polar-bear`) 로 선택.
- **polar-bear**: **유용한 정보 제공 0%, 헛소리 100%** 페르소나. 질문에 절대 답하지 않고, 질문의 단어 하나만 훔쳐서 자기 추억·풍경·푸념으로 증발시킴. "답은 하되 비중 최소" 가 아니라 **"답 자체 금지"**. 예외는 자살·자해·폭력 등 즉각적 생명 위협 상황뿐.
- 새 페르소나 추가는 `.md` 파일 드롭 + active-template 교체 + 재배포만.

## 8. 진행 상태 체크리스트

### 완료
- [x] EC2 + docker-compose 스택 + GitHub Actions CI/CD
- [x] Prometheus + Grafana (data source 이름 `Prometheus`, 대시보드 `admhkqk` JVM Micrometer)
- [x] Firebase Auth (익명) + Admin SDK 토큰 검증
- [x] Vertex AI Gemini 2.5 Flash 연동
- [x] 탄소 누적 + 녹아내림 단계
- [x] 멀티턴 대화 + 이력 기반 컨텍스트 창 축소
- [x] Swagger 문서화 (Bearer/X-Dev-Uid scheme, 엔드포인트별 상세)
- [x] 공통 ErrorResponse 포맷 (ControllerAdvice + SecurityEntryPoint)
- [x] temperature/topP/maxOutputTokens 설정
- [x] Dev 우회 필터 (`X-Dev-Uid` + `DEV_AUTH_BYPASS` flag)

### 기획 확정 대기
- [ ] **Q1: 회원가입 범위** — 익명만 / Google 추가 / 이메일 가입까지. 현재 익명만.
- [ ] **Q2: 탄소 환산 계수** — 팀이 논문 기반으로 정할 예정. 현재는 Google 2025 리포트 근사.
- [ ] **Q3: 자연 파괴 영수증 엔드포인트** — stage 5 도달 시 표시할 `/api/me/receipt`. 미구현.

### 다음 합리적 작업 후보
1. 대화 제목 자동 생성 (첫 메시지 기반 Gemini 에게 10자 이내 title 요청)
2. README 정비 (현재는 Spring 기본 그대로)
3. Flyway 도입 (`ddl-auto=update` 대체) — 해커톤 이후
4. Grafana provisioning 코드화 (볼륨 삭제 시 data source/dashboard 날아감) — 해커톤 이후

## 9. 자주 밟는 함정

| 함정 | 증상 | 해결 |
|------|------|------|
| Windows `curl -d '{한글}'` | 서버 `Invalid UTF-8 start byte 0xba` → 400 | ASCII 로 테스트하거나 파일로 `-d @file.json` |
| `DEV_AUTH_BYPASS=true` 프로덕션 유출 | 누구나 임의 uid 행세 가능 | 해커톤 종료 후 `.env` 에서 제거·재배포 |
| `GEMINI_RAW_PUBLIC=true` 방치 | 누구나 Gemini 호출 가능 → 빌링 폭탄 위험 | 프롬프트 엔지니어링 세션 끝나면 즉시 false 로 되돌리고 재배포 |
| SA key 를 리포에 저장 | bot 이 분 단위로 스캔해서 오남용 | `.gitignore` 의 `gdg-hackathon-*.json`, `*-sa.json`, `gcp-*.json` 패턴 유지 |
| Gemini billing 끊김 | 502 `code=GEMINI_UPSTREAM` | https://console.developers.google.com/billing/enable?project=gdg-hackathon-494307 |
| 프론트 Firebase 프로젝트 불일치 | 토큰 aud mismatch → 401 | 반드시 `gdg-hackathon-494307` 프로젝트의 Web 앱 config 사용 |
| Spring Security 7 `AbstractHttpConfigurer::disable` method reference | CSRF 가 안 꺼짐 → POST 만 403 | 람다 형태 `csrf -> csrf.disable()` 사용 (현재 코드 OK) |
| Actions 배포 대기 시간 비교 | 문자열 비교 시 타임존·포맷 주의 | `target_after=<이전 StartedAt>` 으로 strict `>` 비교 |

## 10. 외부 참조

- **백엔드 리포**: https://github.com/GDG-Hackathon-2026/GDG-Hackathon-2026-Backend (main = 자동 배포)
- **프론트 리포**: https://github.com/GDG-Hackathon-2026/GDG-Hackathon-2026-Front (Next.js + TypeScript, Firebase Auth)
- **프론트 인증 가이드**: [docs/FIREBASE_AUTH_GUIDE.md](docs/FIREBASE_AUTH_GUIDE.md)
- **Docker Hub**: `jiseong02/gdg-2026-backend`
- **EC2**: SSH alias `Lion-2026gdg` (config 는 로컬, key `2026_GDG.pem` 은 민감 — 팀원에게 요청)
- **Grafana**: http://13.125.197.228:3000 (admin / `gdg1234`)
- **Prometheus**: http://13.125.197.228:9090

## 11. 세션 재개시 루틴

```bash
# 1. 배포 상태 확인
curl -s http://13.125.197.228:8080/actuator/health

# 2. 컨테이너 동작
ssh Lion-2026gdg 'docker ps --format "{{.Names}}\t{{.Status}}"'

# 3. 최근 로그
ssh Lion-2026gdg 'docker logs gdg-app --tail 30 2>&1 | grep -v WARNING | tail -20'

# 4. DevAuth 로 빠른 테스트
curl http://13.125.197.228:8080/api/me -H "X-Dev-Uid: test-user-1"
```

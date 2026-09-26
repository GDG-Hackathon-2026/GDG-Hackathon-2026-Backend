# LLM의 환경 영향을 체감하는 AI 챗봇: 백엔드

Build with AI x GDG Busan 2026 AI Hackathon에서 만든 챗봇의 백엔드입니다. 대화에 쓴 토큰을 탄소 배출량(gCO₂eq)으로 환산해 사용자별로 쌓고, 많이 쓸수록 LLM에 보내는 문맥을 줄여 환경 비용을 체감하게 합니다.

## 무엇을 하나
- 대화마다 입력·출력 토큰을 gCO₂eq로 환산해 사용자별로 누적합니다
- 누적량에 따라 LLM에 보내는 입력 토큰 상한을 단계적으로 줄이고, 500g을 넘으면 요청을 거부합니다(402)
- 전체 사용자의 탄소 사용량 통계를 제공합니다

## 탄소 환산과 단계

기본값이며 환경변수로 조정합니다.
- 입력 토큰 1,000개당 0.015 gCO₂eq, 출력 토큰 1,000개당 0.025 gCO₂eq

| 단계 | 누적 탄소 (gCO₂eq) | 허용 입력 토큰 |
|---|---|---|
| 0 | 20 미만 | 8,192 |
| 1 | 20 ~ 50 | 4,096 |
| 2 | 50 ~ 100 | 2,048 |
| 3 | 100 ~ 200 | 1,024 |
| 4 | 200 ~ 500 | 512 |
| 5 | 500 이상 | 0 (요청 거부) |

## API
| 메서드 | 경로 | 설명 |
|---|---|---|
| POST / GET | `/api/conversations` | 대화 생성, 목록 |
| GET | `/api/conversations/{id}` | 대화 조회 |
| POST | `/api/conversations/{id}/messages` | 메시지 전송 (단계에 맞춰 문맥 축소) |
| GET | `/api/me` | 내 누적 탄소와 단계 |
| POST | `/api/me/carbon/reset` | 누적 탄소 초기화 |
| GET | `/api/stats/carbon` | 전체 사용자 탄소 통계 |
| GET | `/api/personas` | 대화 페르소나 목록 |

인증은 Firebase Authentication ID 토큰(`Authorization: Bearer`)을 씁니다. 전체 명세는 실행 후 Swagger UI에서 볼 수 있습니다.

## 기술
Java 21 · Spring Boot 4 · Spring Data JPA · MySQL 8 · Gemini · Firebase Authentication · springdoc-openapi · Docker Compose · Prometheus · Grafana · AWS EC2

## 실행과 배포
- 로컬: Java 21, `./gradlew bootRun` (환경변수는 `application.yml` 참고)
- 서버: `docker-compose.yml`로 앱, MySQL, Prometheus, Grafana를 함께 띄웁니다
- 배포: `main` 브랜치 push 시 자동 배포
- 새 환경 설정: [docs/ONBOARDING.md](docs/ONBOARDING.md)

## 역할
백엔드 1인 개발 (나지성)

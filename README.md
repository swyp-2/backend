# BATON Backend

매장 근무표 + 크루원 대타 요청 + 매니저 최종 승인 MVP의 백엔드.

Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Flyway · Spring Security · springdoc-openapi

## 로컬 실행

필요: JDK 21, Docker (Docker Desktop)

```bash
# 1. PostgreSQL 16 (DB baton, 포트 5432, 볼륨 baton-pgdata 유지)
docker compose up -d

# 2. 애플리케이션 (local 프로필)
./gradlew bootRun --args='--spring.profiles.active=local'
```

Windows PowerShell에서는 `.\gradlew.bat bootRun --args="--spring.profiles.active=local"`.

확인:
- 헬스체크: http://localhost:8080/health → `{"data":{"status":"UP"},"meta":{"server_time":"..."}}`
- API 문서: http://localhost:8080/swagger-ui.html

DB를 초기화하려면 `docker compose down -v`.

## 테스트

```bash
./gradlew test
```

Testcontainers가 `postgres:16` 컨테이너를 직접 띄우므로 Docker만 실행 중이면 된다 (docker compose 불필요).

## 프로필

| 프로필 | 용도 | DB 접속 |
|---|---|---|
| `local` | 로컬 개발 | docker-compose의 localhost:5432/baton |
| `test` | 테스트 | Testcontainers (`@ServiceConnection`) |
| `prod` | 운영 | 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |

비밀 값은 설정 파일에 쓰지 않고 환경변수로만 주입한다.

## CI/CD

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `.github/workflows/ci.yml` | develop·main 대상 PR | `./gradlew test` |
| `.github/workflows/deploy.yml` | develop push(머지) | 테스트 → EC2 SSH → 테스트한 커밋 checkout → `docker compose -f docker-compose.prod.yml up -d --build` → `/health` UP 확인 |

배포에 필요한 저장소 시크릿: `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`, `EC2_APP_DIR`(서버의 레포 클론 경로).
운영 비밀 값은 EC2의 `.env`에만 둔다(`BATON_JWT_SECRET`, `BATON_GOOGLE_CLIENT_IDS`, `BATON_APPLE_CLIENT_IDS`, DB 접속 정보 등).

자동 배포는 서버 레포를 배포한 커밋에 고정(detached HEAD)한다. 서버에서 수동 배포할 때는 브랜치로 먼저 돌아간다.

```bash
git checkout develop && git pull origin develop
docker compose -f docker-compose.prod.yml up -d --build
```

## 문서

- `CLAUDE.md`: 개발 규칙
- `docs/`: PRD, 기능명세서, ERD, API 계약 초안, 백엔드 분담
# backend

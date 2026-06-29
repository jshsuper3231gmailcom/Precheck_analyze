# CLAUDE.md

## 역할

`@Scheduled` + `@Async` + `@Retryable` 배치 서버. HTTP 포트 없음. `TB_COLLECT_LOG`를 정책 파일(`PreCheck_AnalyzePolicy.conf`) 기준으로 판정 후 `TB_ANALYZE_RESULT` INSERT. 상세는 `FLOW.md`.

---

## 명령어

```bash
# 기본 프로파일: test (PostgreSQL localhost)
gradlew.bat bootRun

# 빌드 / 테스트
gradlew.bat build
gradlew.bat test
```

---

## 핵심 gotcha

- **`@Retryable` 별도 빈 필수**: `AnalyzeService` → `AnalyzeRetryService`. `this.method()` 직접 호출 시 AOP 프록시 우회 → 재시도 불동작
- **`PolicyLoader`는 기동 시 1회 로딩** (`@PostConstruct`) — 정책 파일(`PreCheck_AnalyzePolicy.conf`) 변경 후 반드시 재기동
- **정책 미등록 LOG_ID** → `ANALYZE_LEVEL='미분석'` 저장, `notify` 통보 대상 제외
- **주기 분석 증분 기준**: `TB_ANALYZE_HISTORY.lastAnalyzeLogId` — 이전 성공 이후 신규 로그만 처리 (중복 분석 방지)
- **수치형 경고 구간**: `[수치][<][90][20]` → 72 이상~90 미만 경고(임계치의 20% 구간), 90 이상 에러
- **스케줄 파일 중복 `serverId+path`**: 마지막 항목 우선 (`LinkedHashMap` remove→put 처리)
- **크래시 감지**: 분석 시작 직전 `STATUS=FAIL, FAIL_REASON=IN_PROGRESS` INSERT — 재기동 시 수동 확인

## 설정 파일 기본 경로

경로 미설정(`application.yml`에 없을 때) 기본값:
- `{user.home}/cfg/PreCheck_AnalyzeLogs_Schedule.conf`
- `{user.home}/cfg/PreCheck_AnalyzePolicy.conf`

로컬 테스트용 샘플: `analyze_sample/`, `schedule_sample/`

## 스택 참고

- Java 17, Spring Boot 3.5.14, MyBatis 3.0.5 (XML 매퍼), Spring Retry
- PostgreSQL (test) / Altibase (prod)
- `analyzeTaskExecutor` 스레드풀: core=5, max=20, queue=50
- 재시도: `maxAttempts=4`, delay=300,000ms (5분)

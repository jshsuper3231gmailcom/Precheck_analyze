# PreCheck Analyze 개발자 참고 문서

> 생성일: 2026-06-16 | 대상 경로: `precheck_collect/analyze`

---

## 1. 프로젝트 개요

### 목적 및 역할

PreCheck Analyze는 `collect` 프로젝트가 수집하여 `TB_COLLECT_LOG`에 저장한 서버 로그를,
분석 정책 파일(`PreCheck_AnalyzePolicy.conf`)에 따라 판정하고 결과를 `TB_ANALYZE_RESULT`에 적재하는
**배치/주기 실행형 로그 분석 서버**다.

분석 결과는 `dashboard` 프로젝트가 읽어 에러/경고/정상 현황, 히스토리, 리소스 도넛 차트 등으로 시각화한다.
별도 HTTP 서버나 REST API는 없으며, `@Scheduled` 기반 스케줄러가 60초마다 실행 여부를 판단해 분석을 트리거한다.

### 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.5.14 |
| Logging | Log4j2 (spring-boot-starter-log4j2, starter-logging 제외) |
| ORM | MyBatis 3.0.5 |
| 재시도 | Spring Retry + Spring AOP |
| DB (테스트) | PostgreSQL 5432 |
| DB (운영) | Altibase (JDBC 8.1.0) |
| Build | Gradle 8 |
| 기타 | Lombok, HikariCP, Spring DevTools |

### 실행 방식

**백그라운드 배치 서버** — `AnalyzeApplication.main()` 진입점. HTTP 포트 없음.

```bash
# 테스트 환경 (PostgreSQL)
./gradlew bootRun --args='--spring.profiles.active=test'

# 운영 환경 (Altibase)
./gradlew bootRun --args='--spring.profiles.active=prod'
```

기동 시 `PolicyLoader.load()`(`@PostConstruct`)가 정책 파일을 메모리에 로딩한다.
이후 `AnalyzeScheduler.run()`이 60초마다 실행되어 스케줄 파일을 점검하고,
실행 조건을 만족하는 스케줄에 대해 비동기(`@Async`)로 분석 작업을 시작한다.

---

## 2. 데이터 흐름

### 전체 흐름도

```
[collect 프로젝트] ──→ TB_COLLECT_LOG (수집된 서버 로그 원문)

[analyze 프로젝트]
  ┌─ 기동 시 ─────────────────────────────────────────────────────┐
  │  PolicyLoader (@PostConstruct)                                 │
  │  ├─ PreCheck_AnalyzePolicy.conf 읽기                           │
  │  └─ policyMap: { "serverId:logId" → AnalyzePolicy } 로 캐싱   │
  └────────────────────────────────────────────────────────────────┘

  ┌─ 매 60초 ─────────────────────────────────────────────────────┐
  │  AnalyzeScheduler.run()                                        │
  │  ├─ AnalyzeScheduleParser: PreCheck_AnalyzeLogs_Schedule.conf  │
  │  │  (60초마다 캐시 재검사, 변경 있으면 파일 재파싱)            │
  │  └─ 실행 조건 만족 시 AnalyzeService.analyze() @Async 호출     │
  └────────────────────────────────────────────────────────────────┘

  ┌─ 분석 실행 (비동기 스레드) ────────────────────────────────────┐
  │  AnalyzeService.analyze()                                       │
  │  ├─ TB_ANALYZE_HISTORY INSERT (FAIL/IN_PROGRESS 선등록)        │
  │  └─ AnalyzeRetryService.analyzeWithRetry() 위임                │
  │       ├─ [배치] TB_COLLECT_LOG selectForAnalyze (오늘 전체)    │
  │       ├─ [주기] TB_COLLECT_LOG selectAfterLogId (이전 성공 이후) │
  │       ├─ 각 로그에 대해 LogAnalyzer.analyze() 호출             │
  │       │    └─ 판정: 정상/경고/에러/정보/미분석                  │
  │       ├─ TB_ANALYZE_RESULT INSERT                               │
  │       └─ TB_ANALYZE_HISTORY UPDATE (SUCCESS/FAIL 최종 기록)    │
  │  실패 시: @Retryable → 300초 간격 3회 재시도 → @Recover         │
  └────────────────────────────────────────────────────────────────┘

[dashboard 프로젝트] ←── TB_ANALYZE_RESULT, TB_ANALYZE_HISTORY 조회
```

### 주요 시나리오별 흐름

#### 배치 분석 (하루 1회, 지정 시각)

```
스케줄 파일 예시: [dcoodb01-주문체결][주기|1-5|000501|1|230501]
                                      ↑배치아님, 주기임

배치 예시(파일 내): [배치|1-5|080000]  → 평일 08:00:00 1회 실행

AnalyzeScheduler.shouldRunBatch()
  └─ 오늘 요일 일치? + 시각 범위(80초 창) 이내? + 오늘 이미 실행했음?
      → collectLogMapper.selectForAnalyze(today, serverId, sourceFilePath)
         [NOT EXISTS TB_ANALYZE_RESULT 조건 포함 — 기분석 로그 재처리 방지]
```

#### 주기 분석 (startTime ~ endTime 사이, intervalMinutes마다)

```
스케줄 예시: [주기|1-5|000501|1|230501]
              → 평일 00:05:01 ~ 23:05:01 사이 1분 간격

AnalyzeScheduler.shouldRunPeriodic()
  └─ runIndex = (nowSeconds - startSeconds) / intervalSeconds
     → 같은 runIndex에서 중복 실행 방지 (lastPeriodicRunIndexByKey 맵)

AnalyzeRetryService.analyzeInternal()
  └─ 주기: analyzeHistoryMapper.selectLastSuccess() → lastAnalyzeLogId 조회
     └─ collectLogMapper.selectAfterLogId(today, serverId, path, lastLogId)
        → 이전 성공 분석 이후 신규 로그만 처리 (중복 분석 방지)
```

#### 분석 로직 분기 (logType별 Analyzer)

```
AnalyzeRetryService.analyzeOne(CollectLog)
  └─ PolicyLoader.findPolicy(serverId, logId)
      ├─ null → buildUnanalyzedResult() → 미분석 반환
      └─ policy found →
          logType="문구" → PhraseAnalyzer  → 에러 키워드 포함 여부
          logType="수치" → NumericAnalyzer → 임계치 비교, 경고 구간 계산
          logType="날짜" → DateAnalyzer    → 로그 내 날짜 == 오늘 비교
          logType="존재" → ExistenceAnalyzer → 로그 존재 자체가 에러
          logType="정보" → InfoAnalyzer    → 항상 정보 레벨 반환
          logType="비교" → CompareAnalyzer → $A$ == $B$ 두 수치 일치 여부
          logType="시간" → TimeAnalyzer    → HH:mm 시간 임계치 비교
```

---

## 3. 디렉토리 및 파일 구조

### 디렉토리 역할

```
analyze/
├── src/main/java/com/sks/precheck/analyze/
│   ├── analyzer/        LogAnalyzer 인터페이스 + 7개 구현체 (logType별 분석)
│   ├── common/
│   │   ├── constants/   AnalyzeConstants — 로그 타입, 레벨, 상태 상수
│   │   ├── exception/   AnalyzeException — 재시도 트리거용 도메인 예외
│   │   └── util/        DateUtil, SequenceHelper
│   ├── config/          AsyncConfig, DataSourceConfig, MyBatisConfig,
│   │                    PolicyLoader, RetryConfig
│   ├── domain/          AnalyzeHistory, AnalyzeResult, CollectLog (도메인 VO)
│   │   └── policy/      7개 정책 도메인 (AnalyzePolicy 인터페이스 + 구현체)
│   ├── mapper/          MyBatis Mapper 인터페이스 3개
│   ├── parser/          AnalyzePolicyParser, AnalyzeScheduleParser
│   ├── scheduler/       AnalyzeScheduler — @Scheduled 진입점
│   ├── service/         AnalyzeService, AnalyzeRetryService
│   └── vo/              AnalyzeScheduleVo
├── src/main/resources/
│   ├── application.yml         기본 설정 (MyBatis, 프로파일)
│   ├── application-test.yml    PostgreSQL 테스트 환경
│   ├── application-prod.yml    Altibase 운영 환경 + 파일 경로
│   ├── cfg/                    (빈 디렉토리, 운영 서버에 실제 conf 파일 위치)
│   ├── mapper/                 MyBatis XML 쿼리 3개
│   └── log4j2-spring.xml       로그 설정 (파일 롤링, 콘솔)
├── analyze_sample/             분석 정책 파일 샘플
│   ├── PreCheck_AnalyzePolicy.conf       운영 정책 샘플
│   └── PreCheck_AnalyzePolicy_jcm.conf  테스트용 정책 샘플
├── schedule_sample/            스케줄 파일 샘플
│   └── PreCheck_AnalyzeLogs_Schedule.conf
├── logs/                       로그 파일 (precheck-analyze.log + 날짜별 gz)
└── scripts/                    보조 스크립트
    └── fix-terminal-encoding.ps1
```

### 주요 파일 목록

| 파일 | 역할 |
|------|------|
| `AnalyzeApplication.java` | Spring Boot 진입점 (`@EnableAsync`, `@EnableRetry`) |
| `scheduler/AnalyzeScheduler.java` | 60초마다 스케줄 파일 점검 후 분석 트리거 |
| `service/AnalyzeService.java` | 분석 이력 선등록 후 AnalyzeRetryService 위임 |
| `service/AnalyzeRetryService.java` | 실제 분석 수행, @Retryable 재시도, @Recover 마감 |
| `analyzer/LogAnalyzer.java` | 분석기 공통 인터페이스 (`analyze(CollectLog, AnalyzePolicy)`) |
| `analyzer/PhraseAnalyzer.java` | 문구형 — 에러 키워드 포함 시 에러 |
| `analyzer/NumericAnalyzer.java` | 수치형 — 임계치 비교 + 경고 구간(warningRatio%) |
| `analyzer/DateAnalyzer.java` | 날짜형 — 로그 내 날짜가 오늘인지 확인 |
| `analyzer/ExistenceAnalyzer.java` | 존재형 — 로그 존재 자체가 에러 |
| `analyzer/InfoAnalyzer.java` | 정보형 — 항상 정보 레벨 반환 |
| `analyzer/CompareAnalyzer.java` | 비교형 — $A$ == $B$ 두 수치 동일 여부 |
| `analyzer/TimeAnalyzer.java` | 시간형 — HH:mm 임계치 비교 |
| `config/PolicyLoader.java` | 정책 파일 기동 시 1회 로딩, "serverId:logId" 키 O(1) 조회 |
| `config/AsyncConfig.java` | analyzeTaskExecutor 스레드풀 (core5, max20, queue50) |
| `config/RetryConfig.java` | Spring Retry 활성화 설정 |
| `parser/AnalyzePolicyParser.java` | 정책 파일 라인 파서 (`[serverId][logId][타입][...]`) |
| `parser/AnalyzeScheduleParser.java` | 스케줄 파일 파서, 중복 serverId+path는 마지막이 최종 |
| `domain/policy/AnalyzePolicy.java` | 정책 도메인 인터페이스 |
| `common/constants/AnalyzeConstants.java` | 로그 타입/레벨/상태/재시도 상수 |
| `common/util/SequenceHelper.java` | DB 시퀀스 nextval 조회 (PostgreSQL/Altibase 분기) |
| `mapper/AnalyzeHistoryMapper.xml` | 이력 INSERT/UPDATE/마지막 성공 SELECT |
| `mapper/CollectLogMapper.xml` | 배치용 전체 조회, 주기용 LogId 이후 조회 |
| `mapper/AnalyzeResultMapper.xml` | 분석 결과 INSERT |
| `analyze_sample/PreCheck_AnalyzePolicy.conf` | 정책 파일 포맷 실제 샘플 (7종 타입 전부 포함) |
| `schedule_sample/PreCheck_AnalyzeLogs_Schedule.conf` | 스케줄 파일 포맷 샘플 |

---

## 4. 소스별 주요 함수/메서드

### AnalyzeScheduler

| 메서드 | 설명 |
|--------|------|
| `run()` | `@Scheduled(fixedDelay=60000)` — 스케줄 목록 로드 후 shouldRun 판단, 통과 시 analyze() 호출 |
| `getSchedules()` | 60초 캐시 적용 스케줄 파일 로드 (캐시 만료 시 재파싱) |
| `shouldRun(schedule, now)` | 요일 일치 → 배치/주기 분기 호출 |
| `shouldRunBatch(...)` | 오늘 실행 여부 판단 (`lastBatchRunDateByKey` 중복 방지) |
| `shouldRunPeriodic(...)` | runIndex 기반 구간 판단 (`lastPeriodicRunIndexByKey` 중복 방지) |
| `isTodayMatched(daySpec, date)` | `*`, `0-6`, `1-5`, 단일 숫자 요일 스펙 파싱 (0=일, 1=월~6=토) |
| `parseTime(hhmmss)` | `HHmmss` 6자리 → `LocalTime` 변환 |
| `buildScheduleKey(schedule)` | serverId + path + type + day + start + interval + end 조합 키 |

### AnalyzeService

| 메서드 | 설명 |
|--------|------|
| `analyze(scheduleVo)` | `@Async("analyzeTaskExecutor")` — 이력 선등록 후 AnalyzeRetryService 위임 |
| `parseScheduleType(type)` | "배치"/"주기" 검증, 그 외 AnalyzeException 투척 |

### AnalyzeRetryService

| 메서드 | 설명 |
|--------|------|
| `analyzeWithRetry(...)` | `@Retryable(AnalyzeException, maxAttempts=4, delay=300000ms)` — analyzeInternal 호출, 실패 시 재시도 |
| `analyzeInternal(...)` | 배치/주기 분기 후 로그 조회 → 각 로그 analyzeOne() → 결과 INSERT → 이력 UPDATE |
| `analyzeOne(collectLog)` | PolicyLoader.findPolicy() → logType별 Analyzer 위임 → 미분석 fallback |
| `buildUnanalyzedResult(log)` | 정책 미등록 시 미분석 AnalyzeResult 생성 |
| `recover(e, ...)` | `@Recover` — 최대 재시도 소진 후 이력 FAIL 업데이트, 에러 로그 |

### PolicyLoader

| 메서드 | 설명 |
|--------|------|
| `load()` | `@PostConstruct` — 정책 파일 읽기, "serverId:logId" 키로 HashMap 구성 |
| `findPolicy(serverId, logId)` | O(1) 정책 조회 |
| `getPolicyMap()` | 불변 뷰 반환 (테스트/디버깅용) |

### AnalyzePolicyParser

| 메서드 | 설명 |
|--------|------|
| `parse(line)` | 한 줄 파싱 → 타입 판별 → 타입별 Policy 객체 반환 (실패 시 null) |
| `parsePhrasePolicy(...)` | `[문구][에러키워드,...]` 파싱 |
| `parseNumericPolicy(...)` | `[수치][연산자][임계치][경고비율]` 파싱 |
| `parseDatePolicy(...)` | `[날짜]` — 파라미터 없음 |
| `parseExistencePolicy(...)` | `[존재]` — 파라미터 없음 |
| `parseInfoPolicy(...)` | `[정보]` — 파라미터 없음 |
| `parseComparePolicy(...)` | `[비교]` — 파라미터 없음 |
| `parseTimePolicy(...)` | `[시간][연산자][HH:mm]` 파싱 |
| `extractBracketTokens(text)` | `[...]` 괄호 토큰 추출 (순서 보장) |

### AnalyzeScheduleParser

| 메서드 | 설명 |
|--------|------|
| `parseScheduleFile(filePath)` | 파일 전체 읽기, 중복 serverId+path는 LinkedHashMap remove→put으로 마지막이 우선 |
| `parseLine(line, lineNo)` | `[serverId][sourceFilePath?][배치\|주기\|...]` 형식 파싱 |
| `parseScheduleExpression(expr)` | `배치\|요일\|시작시간` 또는 `주기\|요일\|시작\|간격\|종료` 파싱 |
| `isValidDaySpec(daySpec)` | `*`, 단일 숫자, `0-6` 범위 형식 검증 |
| `isValidTimeHhmmss(text, isStart)` | 6자리 숫자 시간 포맷 검증 |

### Analyzer 구현체 공통 패턴

모든 Analyzer는 `LogAnalyzer.analyze(CollectLog log, AnalyzePolicy policy)` → `AnalyzeResult` 구조.

| Analyzer | logType | 판정 로직 |
|----------|---------|-----------|
| `PhraseAnalyzer` | 문구 | `logContent`에 에러 키워드 포함 시 에러, 없으면 정상 |
| `NumericAnalyzer` | 수치 | `logValue` (또는 `$값$` 파싱) vs threshold + operator. 경고 구간 = threshold ± warningRatio% |
| `DateAnalyzer` | 날짜 | `logContent`에서 `yyyy/MM/dd` 추출, 오늘 날짜와 비교 |
| `ExistenceAnalyzer` | 존재 | 로그 존재 자체가 에러 (파일/프로세스 부재를 의미) |
| `InfoAnalyzer` | 정보 | 항상 정보 레벨 반환 (분석 없이 저장) |
| `CompareAnalyzer` | 비교 | `logContent`/`rawLog`에서 `$A$$B$` 두 숫자 파싱 후 동일 여부 비교 |
| `TimeAnalyzer` | 시간 | `$HH:mm$` 토큰 파싱 후 policy.operator와 thresholdTime 비교 |

### SequenceHelper

| 메서드 | 설명 |
|--------|------|
| `nextval(sequenceName)` | DB 시퀀스 다음 값 조회. PostgreSQL: `nextval('seq')`, Altibase: `seq.NEXTVAL FROM DUAL` |

---

## 5. 리소스 및 DB 환경

### DB 연결 정보

| 환경 | Driver | URL | 계정 |
|------|--------|-----|------|
| 테스트 (`-test`) | `org.postgresql.Driver` | `jdbc:postgresql://localhost:5432/postgres` | `postgres` |
| 운영 (`-prod`) | `Altibase.jdbc.driver.AltibaseDriver` | `jdbc:Altibase://192.168.0.1:20300/precheck` | `precheck` |

> 운영 비밀번호: `application-prod.yml`에 `precheck` (운영 시 별도 관리 권장).

### 커넥션 풀 (HikariCP)

| 환경 | 설정 | 값 |
|------|------|-----|
| 테스트 | `initialization-fail-timeout` | `-1` (DB 없어도 기동 허용) |
| 운영 | HikariCP 기본값 | 별도 튜닝 설정 없음 |

### 비동기 스레드풀 (analyzeTaskExecutor)

| 설정 | 값 | 의미 |
|------|-----|------|
| `corePoolSize` | 5 | 평시 동시 분석 수 |
| `maxPoolSize` | 20 | 최대 동시 분석 수 (서버 최대 100대 기준) |
| `queueCapacity` | 50 | 포화 시 대기 큐 크기 |
| `keepAliveSeconds` | 60 | 유휴 스레드 유지 시간 |
| `threadNamePrefix` | `analyze-async-` | 로그에서 스레드 식별용 |

### Spring Retry 설정

| 설정 | 값 | 의미 |
|------|-----|------|
| `MAX_RETRY_COUNT` | 3 | 최대 재시도 횟수 (maxAttempts = 4 = 최초 1 + 재시도 3) |
| `RETRY_DELAY_MILLISECONDS` | 300,000ms (5분) | 재시도 간격 |
| 대상 예외 | `AnalyzeException` | DB 연결 오류, 분석 로직 오류 |
| `@Recover` | `recover()` | 모두 실패 시 이력을 FAIL로 마감 |

### 사용 테이블

| 테이블 | 이 프로젝트에서의 역할 |
|--------|----------------------|
| `TB_COLLECT_LOG` | SELECT 전용 — 분석 대상 로그 입력원 |
| `TB_ANALYZE_RESULT` | INSERT 전용 — 분석 결과 적재 |
| `TB_ANALYZE_HISTORY` | INSERT + UPDATE — 분석 실행 이력 관리 |

### 외부 파일 리소스

| 파일 | 기본 경로 | 역할 |
|------|-----------|------|
| `PreCheck_AnalyzeLogs_Schedule.conf` | `precheck.analyze.schedule-file-path` 또는 `{user.home}/cfg/...` | 분석 대상 서버별 스케줄 정의 |
| `PreCheck_AnalyzePolicy.conf` | `precheck.analyze.policy-file-path` 또는 `{user.home}/cfg/...` | 서버/LOG_ID별 분석 정책 정의 |

---

## 6. 설정 파일 분석

### `src/main/resources/application.yml` (기본 설정)

| 항목 | 기본값 | 설명 |
|------|--------|------|
| `spring.application.name` | `analyze` | 애플리케이션 식별명 |
| `spring.profiles.active` | `test` | 기본 활성 프로파일 |
| `mybatis.mapper-locations` | `classpath:/mapper/*.xml` | MyBatis XML 위치 |
| `mybatis.type-aliases-package` | `com.sks.precheck.analyze.domain` | 도메인 타입 별칭 패키지 |
| `mybatis.configuration.map-underscore-to-camel-case` | `true` | DB 컬럼 언더스코어 → camelCase 자동 변환 |

### `src/main/resources/application-test.yml` (PostgreSQL 테스트)

| 항목 | 값 | 설명 |
|------|-----|------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/postgres` | 로컬 PostgreSQL |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` | PostgreSQL JDBC |
| `hikari.initialization-fail-timeout` | `-1` | DB 없어도 앱 기동 허용 |
| `precheck.analyze.schedule-file-path` | (로컬 절대경로) | 테스트용 스케줄 파일 경로 |
| `precheck.analyze.policy-file-path` | `...PreCheck_AnalyzePolicy_jcm.conf` | 테스트용 정책 파일 (jcm 버전) |

### `src/main/resources/application-prod.yml` (Altibase 운영)

| 항목 | 값 | 설명 |
|------|-----|------|
| `spring.datasource.url` | `jdbc:Altibase://192.168.0.1:20300/precheck` | 운영 Altibase |
| `spring.datasource.driver-class-name` | `Altibase.jdbc.driver.AltibaseDriver` | Altibase JDBC (별도 jar 필요) |
| `precheck.analyze.schedule-file-path` | (운영 서버 절대경로) | 운영 스케줄 파일 |
| `precheck.analyze.policy-file-path` | (운영 서버 절대경로) | 운영 정책 파일 |

> `precheck.analyze.schedule-file-path`와 `precheck.analyze.policy-file-path`를 설정하지 않으면
> `{user.home}/cfg/PreCheck_AnalyzeLogs_Schedule.conf`, `{user.home}/cfg/PreCheck_AnalyzePolicy.conf`를 기본으로 사용한다.

### `analyze_sample/PreCheck_AnalyzePolicy.conf` (정책 파일 포맷)

| 필드 위치 | 의미 | 예시 |
|-----------|------|------|
| `[0]` | serverId | `dlprem01-테스트개발` |
| `[1]` | logId | `DISK_HOME` |
| `[2]` | logType | `수치` / `문구` / `날짜` / `존재` / `정보` / `비교` / `시간` |
| `[3]` (수치) | 연산자 | `<`, `<=`, `>`, `>=`, `=` |
| `[4]` (수치) | 임계치 | `90` |
| `[5]` (수치) | 경고 비율(%) | `20` → threshold 80% 이상이면 경고 |
| `[3]` (문구) | 에러 키워드 CSV | `오류,Exception,error` |
| `[3]` (시간) | 연산자 | `<=` |
| `[4]` (시간) | 임계치 시간 HH:mm | `08:10` |

**수치형 경고 구간 예시:**
- `[수치][<][90][20]` → logValue < 90 이면 정상, 72 이상이면 경고(90의 20%=18), 90 이상이면 에러

### `schedule_sample/PreCheck_AnalyzeLogs_Schedule.conf` (스케줄 파일 포맷)

| 필드 | 의미 | 예시 |
|------|------|------|
| `[0]` | serverId | `dcoodb01-주문체결` |
| `[1]` (선택) | sourceFilePath | 생략 시 null — 해당 서버 전체 파일 경로 |
| 마지막 `[N]` | 스케줄 표현식 | `주기\|1-5\|000501\|1\|230501` |

**스케줄 표현식 문법:**
```
배치|요일|시작시간(HHmmss)
주기|요일|시작시간(HHmmss)|간격(분)|종료시간(HHmmss)

요일: * = 매일, 0=일, 1=월, ..., 6=토, 1-5=평일
예) 주기|1-5|000501|1|230501 → 평일 00:05:01 ~ 23:05:01 사이 1분 간격
```

### `src/main/resources/log4j2-spring.xml` (로그 설정)

| 설정 | 내용 |
|------|------|
| 콘솔 출력 | 표준 출력 패턴 |
| 파일 출력 | `logs/precheck-analyze.log` — 일별 롤링, gz 압축 보관 |
| 로거 | `com.sks.precheck.analyze` 패키지 기준 |

---

## 7. 로그 타입 및 판정 레벨 상수 요약

### 로그 타입 (AnalyzeConstants)

| 상수 | 값 | 정책 파일 키워드 |
|------|-----|----------------|
| `LOG_TYPE_TEXT` | `"문구"` | `[문구]` |
| `LOG_TYPE_NUMERIC` | `"수치"` | `[수치]` |
| `LOG_TYPE_DATE` | `"날짜"` | `[날짜]` |
| `LOG_TYPE_EXIST` | `"존재"` | `[존재]` |
| `LOG_TYPE_INFO` | `"정보"` | `[정보]` |
| `LOG_TYPE_COMPARE` | `"비교"` | `[비교]` |
| `LOG_TYPE_TIME` | `"시간"` | `[시간]` |

### 분석 레벨

| 상수 | 값 | 의미 |
|------|-----|------|
| `LEVEL_NORMAL` | `"정상"` | 정상 판정 |
| `LEVEL_WARNING` | `"경고"` | 경고 구간 (수치형만 해당) |
| `LEVEL_ERROR` | `"에러"` | 에러 판정 |
| `LEVEL_INFO` | `"정보"` | 정보형 로그 |
| `LEVEL_UNANALYZED` | `"미분석"` | 정책 미등록 또는 파싱 실패 |

### 분석 이력 상태

| 상수 | 값 | 의미 |
|------|-----|------|
| `STATUS_SUCCESS` | `"SUCCESS"` | 정상 완료 |
| `STATUS_FAIL` | `"FAIL"` | 실패 (선등록 시 `IN_PROGRESS`, 최종 실패 시 사유 기재) |
| `STATUS_PARTIAL` | `"PARTIAL"` | 부분 성공 (현재 미사용, 확장 예약) |

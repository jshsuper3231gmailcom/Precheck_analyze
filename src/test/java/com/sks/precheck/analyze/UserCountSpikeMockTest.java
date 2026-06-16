package com.sks.precheck.analyze;

import com.sks.precheck.analyze.common.util.SequenceHelper;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.mapper.AnalyzeResultMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class UserCountSpikeMockTest {

    private static final Logger log = LogManager.getLogger(UserCountSpikeMockTest.class);

    private static final String SERVER_ID   = "pmaster2-마스터";
    private static final String LOG_ID_HTS  = "UC_HTS_COUNT";
    private static final String LOG_ID_MTS  = "UC_MTS_COUNT";
    private static final String LOG_ID_TOTAL = "UC_TOTAL_COUNT";

    // 반복 횟수 — 0이면 Ctrl+C로 종료할 때까지 무한 반복
    private static final int MAX_ITERATIONS = 60;

    // FK 없이 NOT NULL 만족용 fake collect_log_id (실 데이터와 충돌 방지: Long.MAX_VALUE에서 감소)
    private static final AtomicLong FAKE_COLLECT_ID = new AtomicLong(Long.MAX_VALUE - 1_000_000L);

    @Autowired
    private SequenceHelper sequenceHelper;

    @Autowired
    private AnalyzeResultMapper analyzeResultMapper;

    /**
     * 1분 간격으로 HTS/MTS/TOTAL 접속자 수를 TB_ANALYZE_RESULT에 삽입한다.
     *
     * 값 범위:
     *   HTS  : 0 ~ 6000
     *   MTS  : 0 ~ 6000
     *   TOTAL: HTS + MTS (6000 초과 가능)
     *
     * 스파이크 패턴 — 95% 확률: 소폭 랜덤 워크, 5% 확률: 급등/급락
     */
    @Test
    void insertUserCountMockDataEveryMinute() throws InterruptedException {
        Random random = new Random();
        int htsValue  = 2000 + random.nextInt(1000);
        int mtsValue  = 1500 + random.nextInt(1000);

        int count = 0;
        while (MAX_ITERATIONS == 0 || count < MAX_ITERATIONS) {
            LocalDateTime now   = LocalDateTime.now();
            String today        = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            htsValue  = clamp(htsValue  + randomDelta(random, 200, 800), 0, 6000);
            mtsValue  = clamp(mtsValue  + randomDelta(random, 150, 600), 0, 6000);
            int totalValue = htsValue + mtsValue;

            insertResult(LOG_ID_HTS,   BigDecimal.valueOf(htsValue),   now, today);
            insertResult(LOG_ID_MTS,   BigDecimal.valueOf(mtsValue),   now, today);
            insertResult(LOG_ID_TOTAL, BigDecimal.valueOf(totalValue), now, today);

            log.info("[{}/{}] {} → HTS:{}, MTS:{}, TOTAL:{}",
                    count + 1,
                    MAX_ITERATIONS == 0 ? "∞" : MAX_ITERATIONS,
                    now, htsValue, mtsValue, totalValue);

            count++;
            if (MAX_ITERATIONS == 0 || count < MAX_ITERATIONS) {
                Thread.sleep(60_000L);
            }
        }
        log.info("테스트 완료 — {}건 삽입", count * 3);
    }

    /**
     * 과거 데이터를 즉시 대량 삽입 (대시보드 초기 데이터 확인용).
     * minutesBack분 전부터 1분 간격으로 소급 삽입한다.
     */
    @Test
    void backfillUserCountMockData() {
        final int minutesBack = 120;
        Random random = new Random();
        int htsValue  = 2000 + random.nextInt(1000);
        int mtsValue  = 1500 + random.nextInt(1000);

        for (int i = minutesBack; i >= 0; i--) {
            LocalDateTime ts = LocalDateTime.now().minusMinutes(i);
            String today     = ts.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            htsValue  = clamp(htsValue  + randomDelta(random, 200, 800), 0, 6000);
            mtsValue  = clamp(mtsValue  + randomDelta(random, 150, 600), 0, 6000);
            int totalValue = htsValue + mtsValue;

            insertResult(LOG_ID_HTS,   BigDecimal.valueOf(htsValue),   ts, today);
            insertResult(LOG_ID_MTS,   BigDecimal.valueOf(mtsValue),   ts, today);
            insertResult(LOG_ID_TOTAL, BigDecimal.valueOf(totalValue), ts, today);

            log.info("backfill -{:3}분 → HTS:{}, MTS:{}, TOTAL:{}", i, htsValue, mtsValue, totalValue);
        }
        log.info("backfill 완료 — {}건 삽입", (minutesBack + 1) * 3);
    }

    // 5% 확률로 스파이크, 나머지는 정규분포 소폭 변동
    private int randomDelta(Random random, int normalStd, int spikeAmount) {
        if (random.nextDouble() < 0.05) {
            return (random.nextBoolean() ? 1 : -1) * (spikeAmount + random.nextInt(spikeAmount));
        }
        return (int) (random.nextGaussian() * normalStd);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void insertResult(String logId, BigDecimal value, LocalDateTime logTimestamp, String today) {
        AnalyzeResult result = new AnalyzeResult();
        result.setAnalyzeResultId(sequenceHelper.nextval("SEQ_ANALYZE_RESULT"));
        result.setCollectLogId(FAKE_COLLECT_ID.getAndDecrement());
        result.setServerId(SERVER_ID);
        result.setServerIp("TEST-MOCK");
        result.setLogType("수치");
        result.setLogId(logId);
        result.setLogTimestamp(logTimestamp);
        result.setLogContent(value.toPlainString());
        result.setLogValue(value);
        result.setAnalyzeLevel("정상");
        result.setAnalyzeMessage("[MOCK] " + logId + "=" + value.toPlainString());
        result.setAnalyzeDate(today);
        result.setAnalyzeDatetime(logTimestamp);
        result.setCollectDate(today);
        result.setNotifyYn("N");
        result.setCreatedAt(logTimestamp);
        analyzeResultMapper.insert(result);
    }
}

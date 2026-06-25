package com.sks.precheck.analyze;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.precheck.analyze.analyzer.CompareAnalyzer;
import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.ComparePolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CompareAnalyzerTest {

    private final CompareAnalyzer analyzer = new CompareAnalyzer();

    @Test
    void equal_isNormal() {
        ComparePolicy policy = new ComparePolicy();
        policy.setServerId("dlprem01-테스트개발");
        policy.setLogId("JUCHE_DIFF_01");

        CollectLog log = baseCollectLog();
        log.setLogType(AnalyzeConstants.LOG_TYPE_COMPARE);
        log.setLogId("JUCHE_DIFF_01");
        log.setLogContent("실시간정산 #1 수신$123$ 처리$123$");

        AnalyzeResult result = analyzer.analyze(log, policy);
        assertEquals(AnalyzeConstants.LEVEL_NORMAL, result.getAnalyzeLevel());
        assertTrue(result.getAnalyzeMessage().contains("A=123"));
        assertTrue(result.getAnalyzeMessage().contains("B=123"));
        assertTrue(result.getAnalyzeMessage().contains("일치"));
    }

    @Test
    void notEqual_isError() {
        ComparePolicy policy = new ComparePolicy();
        policy.setServerId("dlprem01-테스트개발");
        policy.setLogId("JUCHE_DIFF_01");

        CollectLog log = baseCollectLog();
        log.setLogType(AnalyzeConstants.LOG_TYPE_COMPARE);
        log.setLogId("JUCHE_DIFF_01");
        log.setLogContent("실시간정산 #1 수신$123$ 처리$120$");

        AnalyzeResult result = analyzer.analyze(log, policy);
        assertEquals(AnalyzeConstants.LEVEL_ERROR, result.getAnalyzeLevel());
        assertTrue(result.getAnalyzeMessage().contains("A=123"));
        assertTrue(result.getAnalyzeMessage().contains("B=120"));
        assertTrue(result.getAnalyzeMessage().contains("불일치"));
    }

    @Test
    void logValueNull_canAnalyzeUsingRawLog() {
        ComparePolicy policy = new ComparePolicy();
        policy.setServerId("dlprem01-테스트개발");
        policy.setLogId("JUCHE_DIFF_01");

        CollectLog log = baseCollectLog();
        log.setLogType(AnalyzeConstants.LOG_TYPE_COMPARE);
        log.setLogId("JUCHE_DIFF_01");
        log.setLogValue(null);
        log.setLogContent("실시간정산 #1 수신 처리");
        log.setRawLog("@@@[2026/04/21 12:12:32.123][비교][JUCHE_DIFF_01]|실시간정산 #1 수신$123$ 처리$120$|@@@");

        AnalyzeResult result = analyzer.analyze(log, policy);
        assertEquals(AnalyzeConstants.LEVEL_ERROR, result.getAnalyzeLevel());
        assertTrue(result.getAnalyzeMessage().contains("A=123"));
        assertTrue(result.getAnalyzeMessage().contains("B=120"));
    }

    @Test
    void withinTolerance_isWarning() {
        ComparePolicy policy = new ComparePolicy();
        policy.setServerId("dlprem01-테스트개발");
        policy.setLogId("JUCHE_DIFF_02");
        policy.setToleranceRatio(new BigDecimal("5"));

        CollectLog log = baseCollectLog();
        log.setLogType(AnalyzeConstants.LOG_TYPE_COMPARE);
        log.setLogId("JUCHE_DIFF_02");
        log.setLogContent("실시간정산 #2 수신$99$ 처리$100$");

        AnalyzeResult result = analyzer.analyze(log, policy);
        assertEquals(AnalyzeConstants.LEVEL_WARNING, result.getAnalyzeLevel());
        assertTrue(result.getAnalyzeMessage().contains("허용값 5% 이내"));
    }

    @Test
    void exceedsTolerance_isError() {
        ComparePolicy policy = new ComparePolicy();
        policy.setServerId("dlprem01-테스트개발");
        policy.setLogId("JUCHE_DIFF_02");
        policy.setToleranceRatio(new BigDecimal("5"));

        CollectLog log = baseCollectLog();
        log.setLogType(AnalyzeConstants.LOG_TYPE_COMPARE);
        log.setLogId("JUCHE_DIFF_02");
        log.setLogContent("실시간정산 #2 수신$90$ 처리$100$");

        AnalyzeResult result = analyzer.analyze(log, policy);
        assertEquals(AnalyzeConstants.LEVEL_ERROR, result.getAnalyzeLevel());
    }

    private CollectLog baseCollectLog() {
        CollectLog log = new CollectLog();
        log.setCollectLogId(1L);
        log.setServerId("dlprem01-테스트개발");
        log.setServerIp("127.0.0.1");
        log.setSourceFilePath("/tmp/check.out");
        log.setLogTimestamp(LocalDateTime.now());
        log.setCollectDate("20260527");
        log.setCollectDatetime(LocalDateTime.now());
        log.setScheduleType("배치");
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}


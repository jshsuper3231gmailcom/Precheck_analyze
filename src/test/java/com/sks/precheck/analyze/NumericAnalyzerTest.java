package com.sks.precheck.analyze;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.precheck.analyze.analyzer.NumericAnalyzer;
import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.NumericPolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NumericAnalyzerTest {

    private final NumericAnalyzer analyzer = new NumericAnalyzer();

    @Test
    void operatorLessThan_normal_warning_error() {
        NumericPolicy policy = numericPolicy("<", "100", "20");

        assertEquals(AnalyzeConstants.LEVEL_NORMAL, analyzeLevel(policy, "79"));
        assertEquals(AnalyzeConstants.LEVEL_WARNING, analyzeLevel(policy, "80"));
        assertEquals(AnalyzeConstants.LEVEL_ERROR, analyzeLevel(policy, "100"));
    }

    @Test
    void operatorGreaterThan_normal_warning_error() {
        NumericPolicy policy = numericPolicy(">", "100", "20");

        assertEquals(AnalyzeConstants.LEVEL_ERROR, analyzeLevel(policy, "100"));
        assertEquals(AnalyzeConstants.LEVEL_WARNING, analyzeLevel(policy, "120"));
        assertEquals(AnalyzeConstants.LEVEL_NORMAL, analyzeLevel(policy, "121"));
    }

    @Test
    void operatorEquals_normal_error() {
        NumericPolicy policy = numericPolicy("=", "100", "20");

        assertEquals(AnalyzeConstants.LEVEL_NORMAL, analyzeLevel(policy, "100"));
        assertEquals(AnalyzeConstants.LEVEL_ERROR, analyzeLevel(policy, "99"));
    }

    @Test
    void threshold0_zeroValueIsNormal_notWarning() {
        // threshold=0, warningRatio=1% → warningDelta=0 → 경고 구간 없음 → 0은 정상
        NumericPolicy lePolicy = numericPolicy("<=", "0", "1");
        assertEquals(AnalyzeConstants.LEVEL_NORMAL, analyzeLevel(lePolicy, "0"));
        assertEquals(AnalyzeConstants.LEVEL_ERROR, analyzeLevel(lePolicy, "1"));

        NumericPolicy gePolicy = numericPolicy(">=", "0", "1");
        assertEquals(AnalyzeConstants.LEVEL_NORMAL, analyzeLevel(gePolicy, "0"));
        assertEquals(AnalyzeConstants.LEVEL_ERROR, analyzeLevel(gePolicy, "-1"));
    }

    @Test
    void emptyBracketInContent_filledWithLogValue_regardlessOfInnerWhitespace() {
        NumericPolicy policy = numericPolicy(">", "0", "0");

        assertTrue(analyzeMessage(policy, "12345", "수신[]").contains("수신[12345.00]"));
        assertTrue(analyzeMessage(policy, "12345", "수신 [ ]").contains("수신 [12345.00]"));
        assertTrue(analyzeMessage(policy, "12345", "수신 [  ]").contains("수신 [12345.00]"));
    }

    private String analyzeMessage(NumericPolicy policy, String logValueText, String content) {
        CollectLog log = baseCollectLog();
        log.setLogType(AnalyzeConstants.LOG_TYPE_NUMERIC);
        log.setLogId("DISK_HOME");
        log.setLogContent(content);
        log.setLogValue(new BigDecimal(logValueText));

        AnalyzeResult result = analyzer.analyze(log, policy);
        assertNotNull(result);
        return result.getAnalyzeMessage();
    }

    private String analyzeLevel(NumericPolicy policy, String logValueText) {
        CollectLog log = baseCollectLog();
        log.setLogType(AnalyzeConstants.LOG_TYPE_NUMERIC);
        log.setLogId("DISK_HOME");
        log.setLogContent("home디스크");
        log.setLogValue(new BigDecimal(logValueText));

        AnalyzeResult result = analyzer.analyze(log, policy);
        assertNotNull(result);
        assertEquals(policy.getThreshold(), result.getThresholdValue());
        assertEquals(policy.getOperator(), result.getThresholdOperator());
        assertEquals(policy.getWarningRatio(), result.getWarningRatio());
        return result.getAnalyzeLevel();
    }

    private NumericPolicy numericPolicy(String operator, String threshold, String warningRatio) {
        NumericPolicy policy = new NumericPolicy();
        policy.setServerId("dlprem01-테스트개발");
        policy.setLogId("DISK_HOME");
        policy.setOperator(operator);
        policy.setThreshold(new BigDecimal(threshold));
        policy.setWarningRatio(new BigDecimal(warningRatio));
        return policy;
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


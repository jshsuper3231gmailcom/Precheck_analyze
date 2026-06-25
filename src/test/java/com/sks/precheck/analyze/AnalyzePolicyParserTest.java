package com.sks.precheck.analyze;

import static org.junit.jupiter.api.Assertions.*;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.ComparePolicy;
import com.sks.precheck.analyze.domain.policy.DatePolicy;
import com.sks.precheck.analyze.domain.policy.ExistencePolicy;
import com.sks.precheck.analyze.domain.policy.InfoPolicy;
import com.sks.precheck.analyze.domain.policy.NumericPolicy;
import com.sks.precheck.analyze.domain.policy.PhrasePolicy;
import com.sks.precheck.analyze.domain.policy.TimePolicy;
import com.sks.precheck.analyze.parser.AnalyzePolicyParser;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AnalyzePolicyParserTest {

    private final AnalyzePolicyParser parser = new AnalyzePolicyParser();

    @Test
    void parse_phrase_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][PUSH_STATUS][문구][실패,오류,Fail]");
        assertNotNull(policy);
        assertInstanceOf(PhrasePolicy.class, policy);

        PhrasePolicy phrasePolicy = (PhrasePolicy) policy;
        assertEquals("dlprem01-테스트개발", phrasePolicy.getServerId());
        assertEquals("PUSH_STATUS", phrasePolicy.getLogId());
        assertEquals(AnalyzeConstants.LOG_TYPE_TEXT, phrasePolicy.getLogType());
        assertEquals(3, phrasePolicy.getErrorKeywords().size());
        assertEquals("실패", phrasePolicy.getErrorKeywords().get(0));
        assertEquals("오류", phrasePolicy.getErrorKeywords().get(1));
        assertEquals("Fail", phrasePolicy.getErrorKeywords().get(2));
    }

    @Test
    void parse_numeric_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][DISK_HOME][수치][<][90][20]");
        assertNotNull(policy);
        assertInstanceOf(NumericPolicy.class, policy);

        NumericPolicy numericPolicy = (NumericPolicy) policy;
        assertEquals("dlprem01-테스트개발", numericPolicy.getServerId());
        assertEquals("DISK_HOME", numericPolicy.getLogId());
        assertEquals(AnalyzeConstants.LOG_TYPE_NUMERIC, numericPolicy.getLogType());
        assertEquals("<", numericPolicy.getOperator());
        assertEquals(new BigDecimal("90"), numericPolicy.getThreshold());
        assertEquals(new BigDecimal("20"), numericPolicy.getWarningRatio());
    }

    @Test
    void parse_date_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][DATE_BDAY][날짜]");
        assertNotNull(policy);
        assertInstanceOf(DatePolicy.class, policy);

        DatePolicy datePolicy = (DatePolicy) policy;
        assertEquals("dlprem01-테스트개발", datePolicy.getServerId());
        assertEquals("DATE_BDAY", datePolicy.getLogId());
        assertEquals(AnalyzeConstants.LOG_TYPE_DATE, datePolicy.getLogType());
    }

    @Test
    void parse_existence_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][MEM_MBSOSI][존재]");
        assertNotNull(policy);
        assertInstanceOf(ExistencePolicy.class, policy);

        ExistencePolicy existencePolicy = (ExistencePolicy) policy;
        assertEquals("dlprem01-테스트개발", existencePolicy.getServerId());
        assertEquals("MEM_MBSOSI", existencePolicy.getLogId());
        assertEquals(AnalyzeConstants.LOG_TYPE_EXIST, existencePolicy.getLogType());
    }

    @Test
    void parse_info_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][STOPLOSS_CNT][정보]");
        assertNotNull(policy);
        assertInstanceOf(InfoPolicy.class, policy);

        InfoPolicy infoPolicy = (InfoPolicy) policy;
        assertEquals("dlprem01-테스트개발", infoPolicy.getServerId());
        assertEquals("STOPLOSS_CNT", infoPolicy.getLogId());
        assertEquals(AnalyzeConstants.LOG_TYPE_INFO, infoPolicy.getLogType());
    }

    @Test
    void parse_compare_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][JUCHE_DIFF_01][비교]");
        assertNotNull(policy);
        assertInstanceOf(ComparePolicy.class, policy);

        ComparePolicy comparePolicy = (ComparePolicy) policy;
        assertEquals("dlprem01-테스트개발", comparePolicy.getServerId());
        assertEquals("JUCHE_DIFF_01", comparePolicy.getLogId());
        assertEquals(AnalyzeConstants.LOG_TYPE_COMPARE, comparePolicy.getLogType());
        assertEquals(BigDecimal.ZERO, comparePolicy.getToleranceRatio());
    }

    @Test
    void parse_compare_withToleranceRatio_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][JUCHE_DIFF_02][비교][5]");
        assertNotNull(policy);
        assertInstanceOf(ComparePolicy.class, policy);

        ComparePolicy comparePolicy = (ComparePolicy) policy;
        assertEquals("JUCHE_DIFF_02", comparePolicy.getLogId());
        assertEquals(new BigDecimal("5"), comparePolicy.getToleranceRatio());
    }

    @Test
    void parse_compare_invalidToleranceRatio_returnsNull() {
        assertNull(parser.parse("[dlprem01-테스트개발][JUCHE_DIFF_02][비교][abc]"));
        assertNull(parser.parse("[dlprem01-테스트개발][JUCHE_DIFF_02][비교][-1]"));
    }

    @Test
    void parse_time_ok() {
        AnalyzePolicy policy = parser.parse("[dlprem01-테스트개발][DATE_BTIME][시간][<][08:00]");
        assertNotNull(policy);
        assertInstanceOf(TimePolicy.class, policy);

        TimePolicy timePolicy = (TimePolicy) policy;
        assertEquals("dlprem01-테스트개발", timePolicy.getServerId());
        assertEquals("DATE_BTIME", timePolicy.getLogId());
        assertEquals(AnalyzeConstants.LOG_TYPE_TIME, timePolicy.getLogType());
        assertEquals("<", timePolicy.getOperator());
        assertEquals("08:00", timePolicy.getThresholdTime());
    }

    @Test
    void parse_invalid_format_returnsNull() {
        assertNull(parser.parse("dlprem01-테스트개발][DISK_HOME][수치][<][90][20]"));
        assertNull(parser.parse("[dlprem01-테스트개발][DISK_HOME][수치][<][90]"));
        assertNull(parser.parse("[dlprem01-테스트개발][DISK_HOME]"));
        assertNull(parser.parse("[dlprem01-테스트개발][DISK_HOME][수치][<][not_number][20]"));
        assertNull(parser.parse("[dlprem01-테스트개발][DISK_HOME][수치][<][90][not_number]"));
        assertNull(parser.parse("[dlprem01-테스트개발][DATE_BTIME][시간][=][08:00]"));
        assertNull(parser.parse("[dlprem01-테스트개발][DATE_BTIME][시간][<][8:00]"));
        assertNull(parser.parse("[dlprem01-테스트개발][X][알수없음]"));
    }

    @Test
    void parse_commentLine_returnsNull() {
        assertNull(parser.parse("#[dlprem01-테스트개발][DISK_HOME][수치][<][90][20]"));
        assertNull(parser.parse("   # comment"));
    }
}

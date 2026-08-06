package com.sks.precheck.analyze.parser;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.ComparePolicy;
import com.sks.precheck.analyze.domain.policy.DatePolicy;
import com.sks.precheck.analyze.domain.policy.ExistencePolicy;
import com.sks.precheck.analyze.domain.policy.InfoPolicy;
import com.sks.precheck.analyze.domain.policy.NumericPolicy;
import com.sks.precheck.analyze.domain.policy.PhrasePolicy;
import com.sks.precheck.analyze.domain.policy.TimePolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 분석 정책 파일(PreCheck_AnalyzePolicy.conf) 라인 파서
 *
 * 포맷: [serverId][logId][문구|수치|날짜|존재|정보][타입별 파라미터...]
 * 파싱 실패 시 예외 대신 null을 반환하며, 호출자(PolicyLoader)가 해당 라인을 WARN 후 스킵한다.
 */
@Component
public class AnalyzePolicyParser {

    public AnalyzePolicy parse(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        List<String> tokens = extractBracketTokens(trimmed);
        if (tokens == null || tokens.size() < 3) {
            return null;
        }

        String serverId = tokens.get(0);
        String logId = tokens.get(1);
        String logType = tokens.get(2);

        if (isBlank(serverId) || isBlank(logId) || isBlank(logType)) {
            return null;
        }

        if (AnalyzeConstants.LOG_TYPE_TEXT.equals(logType)) {
            return parsePhrasePolicy(serverId, logId, tokens);
        }
        if (AnalyzeConstants.LOG_TYPE_NUMERIC.equals(logType)) {
            return parseNumericPolicy(serverId, logId, tokens);
        }
        if (AnalyzeConstants.LOG_TYPE_DATE.equals(logType)) {
            return parseDatePolicy(serverId, logId, tokens);
        }
        if (AnalyzeConstants.LOG_TYPE_EXIST.equals(logType)) {
            return parseExistencePolicy(serverId, logId, tokens);
        }
        if (AnalyzeConstants.LOG_TYPE_INFO.equals(logType)) {
            return parseInfoPolicy(serverId, logId, tokens);
        }
        if (AnalyzeConstants.LOG_TYPE_COMPARE.equals(logType)) {
            return parseComparePolicy(serverId, logId, tokens);
        }
        if (AnalyzeConstants.LOG_TYPE_TIME.equals(logType)) {
            return parseTimePolicy(serverId, logId, tokens);
        }

        return null;
    }

    private PhrasePolicy parsePhrasePolicy(String serverId, String logId, List<String> tokens) {
        if (tokens.size() != 4) {
            return null;
        }

        PhrasePolicy policy = new PhrasePolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        policy.setErrorKeywords(splitKeywords(tokens.get(3)));
        return policy;
    }

    private NumericPolicy parseNumericPolicy(String serverId, String logId, List<String> tokens) {
        if (tokens.size() != 6) {
            return null;
        }

        String operator = tokens.get(3);
        String thresholdText = tokens.get(4);
        String warningRatioText = tokens.get(5);

        if (isBlank(operator) || isBlank(thresholdText) || isBlank(warningRatioText)) {
            return null;
        }

        BigDecimal threshold;
        BigDecimal warningRatio;
        try {
            threshold = new BigDecimal(thresholdText.trim());
            warningRatio = new BigDecimal(warningRatioText.trim());
        } catch (NumberFormatException e) {
            return null;
        }

        NumericPolicy policy = new NumericPolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        policy.setOperator(operator.trim());
        policy.setThreshold(threshold);
        policy.setWarningRatio(warningRatio);
        return policy;
    }

    private DatePolicy parseDatePolicy(String serverId, String logId, List<String> tokens) {
        if (tokens.size() != 3) {
            return null;
        }

        DatePolicy policy = new DatePolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        return policy;
    }

    private ExistencePolicy parseExistencePolicy(String serverId, String logId, List<String> tokens) {
        if (tokens.size() != 3) {
            return null;
        }

        ExistencePolicy policy = new ExistencePolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        return policy;
    }

    private InfoPolicy parseInfoPolicy(String serverId, String logId, List<String> tokens) {
        if (tokens.size() != 3) {
            return null;
        }

        InfoPolicy policy = new InfoPolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        return policy;
    }

    private ComparePolicy parseComparePolicy(String serverId, String logId, List<String> tokens) {
        if (tokens.size() == 5) {
            return parseComparePolicyWithOperator(serverId, logId, tokens);
        }

        if (tokens.size() != 3 && tokens.size() != 4) {
            return null;
        }

        BigDecimal toleranceRatio = BigDecimal.ZERO;
        if (tokens.size() == 4) {
            String toleranceText = tokens.get(3);
            if (isBlank(toleranceText)) {
                return null;
            }
            try {
                toleranceRatio = new BigDecimal(toleranceText.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (toleranceRatio.compareTo(BigDecimal.ZERO) < 0) {
                return null;
            }
        }

        ComparePolicy policy = new ComparePolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        policy.setToleranceRatio(toleranceRatio);
        return policy;
    }

    /**
     * 연산자 기반 비교형 — [서버구분][LOG_ID][비교][연산자][허용오차%]
     * 로그의 두 번째 값(B)을 동적 임계치로 삼아 A op (B±허용오차%) 형태로 정상/에러 2단계 판정한다(경고 없음).
     */
    private ComparePolicy parseComparePolicyWithOperator(String serverId, String logId, List<String> tokens) {
        String operator = tokens.get(3);
        String toleranceText = tokens.get(4);

        if (isBlank(operator) || isBlank(toleranceText)) {
            return null;
        }

        String op = operator.trim();
        if (!(">".equals(op) || ">=".equals(op) || "<".equals(op) || "<=".equals(op))) {
            return null;
        }

        BigDecimal toleranceRatio;
        try {
            toleranceRatio = new BigDecimal(toleranceText.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (toleranceRatio.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }

        ComparePolicy policy = new ComparePolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        policy.setOperator(op);
        policy.setToleranceRatio(toleranceRatio);
        return policy;
    }

    private TimePolicy parseTimePolicy(String serverId, String logId, List<String> tokens) {
        if (tokens.size() != 5) {
            return null;
        }

        String operator = tokens.get(3);
        String thresholdTime = tokens.get(4);
        if (isBlank(operator) || isBlank(thresholdTime)) {
            return null;
        }

        String op = operator.trim();
        if (!(">".equals(op) || ">=".equals(op) || "<".equals(op) || "<=".equals(op))) {
            return null;
        }

        String time = thresholdTime.trim();
        if (!isValidTimeHhmm(time)) {
            return null;
        }

        TimePolicy policy = new TimePolicy();
        policy.setServerId(serverId);
        policy.setLogId(logId);
        policy.setOperator(op);
        policy.setThresholdTime(time);
        return policy;
    }

    private List<String> extractBracketTokens(String text) {
        int idx = 0;
        List<String> tokens = new ArrayList<>();

        while (idx < text.length()) {
            int open = text.indexOf('[', idx);
            if (open < 0) {
                break;
            }
            int close = text.indexOf(']', open + 1);
            if (close < 0) {
                return null;
            }
            tokens.add(text.substring(open + 1, close).trim());
            idx = close + 1;
        }

        if (tokens.isEmpty()) {
            return null;
        }
        return tokens;
    }

    private List<String> splitKeywords(String keywordText) {
        List<String> keywords = new ArrayList<>();
        if (keywordText == null) {
            return keywords;
        }

        String[] parts = keywordText.split(",");
        for (String part : parts) {
            String keyword = part.trim();
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private boolean isValidTimeHhmm(String timeText) {
        if (timeText == null || timeText.length() != 5) {
            return false;
        }
        if (timeText.charAt(2) != ':') {
            return false;
        }

        int hh;
        int mm;
        try {
            hh = Integer.parseInt(timeText.substring(0, 2));
            mm = Integer.parseInt(timeText.substring(3, 5));
        } catch (NumberFormatException e) {
            return false;
        }

        if (hh < 0 || hh > 23) {
            return false;
        }
        return mm >= 0 && mm <= 59;
    }
}

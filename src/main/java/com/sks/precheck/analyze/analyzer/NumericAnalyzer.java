package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.NumericPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 수치형 로그 분석기
 *
 * logValue를 정책의 연산자(>, >=, <, <=, =)와 threshold로 비교한다.
 * 조건 불만족 → LEVEL_ERROR, 조건 만족 + threshold 근접(warningRatio% 이내) → LEVEL_WARNING,
 * 그 외 → LEVEL_NORMAL. float/double 오차를 방지하기 위해 BigDecimal로만 계산한다.
 */
@Component
public class NumericAnalyzer implements LogAnalyzer {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int CALC_SCALE = 6;
    private static final Pattern DOLLAR_PATTERN = Pattern.compile("\\$([^$]+)\\$");
    private static final Pattern EMPTY_BRACKET_PATTERN = Pattern.compile("\\[\\s*]");

    @Override
    public AnalyzeResult analyze(CollectLog log, AnalyzePolicy policy) {
        if (!(policy instanceof NumericPolicy)) {
            throw new AnalyzeException("수치형 정책이 아니다 - serverId: " + log.getServerId() + ", logId: " + log.getLogId()
                    + ", 수집로그타입: " + log.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        NumericPolicy numericPolicy = (NumericPolicy) policy;

        BigDecimal logValue = log.getLogValue();
        if (logValue == null) {
            logValue = parseNumericFromLogContent(log.getLogContent());
        }

        BigDecimal threshold = numericPolicy.getThreshold();
        BigDecimal warningRatio = numericPolicy.getWarningRatio();
        String operator = numericPolicy.getOperator();

        if (threshold == null || warningRatio == null || operator == null || operator.isBlank()) {
            throw new AnalyzeException("수치형 정책 필수값 누락 - serverId: " + numericPolicy.getServerId()
                    + ", logId: " + numericPolicy.getLogId());
        }

        if (logValue == null) {
            AnalyzeResult result = baseResult(log);
            result.setAnalyzeLevel(AnalyzeConstants.LEVEL_UNANALYZED);
            result.setAnalyzeMessage("[미분석][" + log.getLogId() + "] 포맷 불일치");
            result.setThresholdValue(threshold);
            result.setThresholdOperator(operator.trim());
            result.setWarningRatio(warningRatio);
            return result;
        }

        String level = decideLevel(logValue, operator.trim(), threshold, warningRatio);

        AnalyzeResult result = baseResult(log);
        result.setAnalyzeLevel(level);
        result.setThresholdValue(threshold);
        result.setThresholdOperator(operator.trim());
        result.setWarningRatio(warningRatio);
        result.setLogValue(logValue);
        result.setAnalyzeMessage(buildMessage(level, log.getLogId(), log.getLogContent(), logValue, operator.trim(), threshold, warningRatio));
        return result;
    }

    private AnalyzeResult baseResult(CollectLog log) {
        AnalyzeResult result = new AnalyzeResult();
        result.setCollectLogId(log.getCollectLogId());
        result.setServerId(log.getServerId());
        result.setServerIp(log.getServerIp());
        result.setLogType(log.getLogType());
        result.setLogId(log.getLogId());
        result.setLogTimestamp(log.getLogTimestamp());
        result.setLogContent(log.getLogContent());
        result.setLogValue(log.getLogValue());
        return result;
    }

    private String decideLevel(BigDecimal logValue, String operator, BigDecimal threshold, BigDecimal warningRatio) {
        // "=" 연산자는 정확한 일치만 판단하며 경고 구간 없음
        if ("=".equals(operator)) {
            return logValue.compareTo(threshold) == 0 ? AnalyzeConstants.LEVEL_NORMAL : AnalyzeConstants.LEVEL_ERROR;
        }

        // 경고 구간 폭 = threshold * (warningRatio / 100)
        BigDecimal warningDelta = threshold
                .multiply(warningRatio)
                .divide(ONE_HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);

        if ("<".equals(operator) || "<=".equals(operator)) {
            // 정상 조건: logValue < threshold (또는 <=)
            // 조건을 만족하지 못하면 즉시 에러
            boolean isError = "<".equals(operator)
                    ? logValue.compareTo(threshold) >= 0
                    : logValue.compareTo(threshold) > 0;

            if (isError) {
                return AnalyzeConstants.LEVEL_ERROR;
            }

            // 경고 구간: [threshold - delta, threshold) — 값이 임계치 하단에서 경고
            // 예) threshold=100, warningRatio=20 → logValue가 80 이상이면 경고
            // warningDelta=0(threshold=0 등)이면 경고 구간이 없으므로 정상 처리
            BigDecimal warningStart = threshold.subtract(warningDelta);
            boolean isWarning = warningDelta.compareTo(BigDecimal.ZERO) > 0
                    && logValue.compareTo(warningStart) >= 0;
            return isWarning ? AnalyzeConstants.LEVEL_WARNING : AnalyzeConstants.LEVEL_NORMAL;
        }

        if (">".equals(operator) || ">=".equals(operator)) {
            // 정상 조건: logValue > threshold (또는 >=)
            // 조건을 만족하지 못하면 즉시 에러
            boolean isError = ">".equals(operator)
                    ? logValue.compareTo(threshold) <= 0
                    : logValue.compareTo(threshold) < 0;

            if (isError) {
                return AnalyzeConstants.LEVEL_ERROR;
            }

            // 경고 구간: (threshold, threshold + delta] — 값이 임계치 상단에서 경고
            // 예) threshold=10, warningRatio=20 → logValue가 12 이하이면 경고
            // warningDelta=0(threshold=0 등)이면 경고 구간이 없으므로 정상 처리
            BigDecimal warningEnd = threshold.add(warningDelta);
            boolean isWarning = warningDelta.compareTo(BigDecimal.ZERO) > 0
                    && logValue.compareTo(warningEnd) <= 0;
            return isWarning ? AnalyzeConstants.LEVEL_WARNING : AnalyzeConstants.LEVEL_NORMAL;
        }

        throw new AnalyzeException("지원하지 않는 연산자: " + operator);
    }

    private String buildMessage(
            String level,
            String logId,
            String content,
            BigDecimal logValue,
            String operator,
            BigDecimal threshold,
            BigDecimal warningRatio
    ) {
        content = fillEmptyBracket(content, logValue);

        if (AnalyzeConstants.LEVEL_WARNING.equals(level)) {
            return "[" + level + "][" + logId + "] " + content + " " + logValue + " " + operator + " " + threshold
                    + "(임계치 대비 " + warningRatio.stripTrailingZeros().toPlainString() + "% 근접)";
        }
        if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
            String opposite = oppositeOperator(operator);
            return "[" + level + "][" + logId + "] " + content + " " + logValue + " " + opposite + " " + threshold + "(임계치)";
        }
        return "[" + level + "][" + logId + "] " + content + " " + logValue + " " + operator + " " + threshold + "(임계치)";
    }

    /**
     * 수집 단계에서 $값$ 토큰이 제거되어 로그 내용에 남은 빈 "[]"를
     * logValue(소수점 2자리)로 채워 보여준다.
     *
     * 원본 로그가 "수신[$12345$]"처럼 대괄호와 토큰 사이에 공백이 없으면 토큰 제거 후
     * 정확히 "[]"가 남지만, "수신 [ $12345$ ]"처럼 공백이 있으면 removeTokensFromContent가
     * 토큰만 지운 뒤 normalizeContent가 공백을 압축해도 "[ ]"(공백 1개)가 남아 "[]"와
     * 매칭되지 않았다. 대괄호 사이 공백 유무와 무관하게 채워지도록 \s*로 허용한다.
     */
    private String fillEmptyBracket(String content, BigDecimal logValue) {
        if (content == null) {
            return content;
        }
        Matcher matcher = EMPTY_BRACKET_PATTERN.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        String replacement = "[" + logValue.setScale(2, RoundingMode.DOWN) + "]";
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String oppositeOperator(String operator) {
        if ("<".equals(operator)) {
            return ">=";
        }
        if ("<=".equals(operator)) {
            return ">";
        }
        if (">".equals(operator)) {
            return "<=";
        }
        if (">=".equals(operator)) {
            return "<";
        }
        if ("=".equals(operator)) {
            return "!=";
        }
        return operator;
    }

    private BigDecimal parseNumericFromLogContent(String logContent) {
        if (logContent == null || logContent.isBlank()) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        Matcher matcher = DOLLAR_PATTERN.matcher(logContent);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains(":")) {
                continue;
            }
            candidates.add(trimmed);
        }

        if (candidates.size() != 1) {
            return null;
        }

        try {
            return new BigDecimal(candidates.get(0));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

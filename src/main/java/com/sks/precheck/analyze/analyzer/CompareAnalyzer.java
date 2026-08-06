package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.ComparePolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CompareAnalyzer implements LogAnalyzer {

    private static final Pattern DOLLAR_PATTERN = Pattern.compile("\\$([^$]+)\\$");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int CALC_SCALE = 6;

    @Override
    public AnalyzeResult analyze(CollectLog log, AnalyzePolicy policy) {
        if (!(policy instanceof ComparePolicy)) {
            throw new AnalyzeException("비교형 정책이 아니다 - serverId: " + log.getServerId() + ", logId: " + log.getLogId()
                    + ", 수집로그타입: " + log.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        ComparePolicy comparePolicy = (ComparePolicy) policy;

        String contentWithTokens = extractContentWithTokens(log);
        ParsedTwoNumbers parsed = parseTwoNumbers(contentWithTokens);
        if (parsed == null) {
            AnalyzeResult result = baseResult(log);
            result.setAnalyzeLevel(AnalyzeConstants.LEVEL_UNANALYZED);
            result.setAnalyzeMessage("[미분석][" + log.getLogId() + "] 포맷 불일치");
            return result;
        }

        BigDecimal toleranceRatio = comparePolicy.getToleranceRatio();
        String operator = comparePolicy.getOperator();
        String level = operator != null
                ? decideLevelByOperator(parsed.a, parsed.b, operator, toleranceRatio)
                : decideLevel(parsed.a, parsed.b, toleranceRatio);

        AnalyzeResult result = baseResult(log);
        result.setAnalyzeLevel(level);
        result.setWarningRatio(toleranceRatio);
        if (operator != null) {
            result.setThresholdValue(parsed.b);
            result.setThresholdOperator(operator);
        }
        result.setAnalyzeMessage(buildMessage(level, log.getLogId(), log.getLogContent(), parsed.a, parsed.b, toleranceRatio, operator));
        return result;
    }

    /**
     * 기준(B) 대비 허용값(%) 이내 차이는 경고, 그 외 불일치는 에러로 판정한다.
     * 허용값 미설정(0)이면 기존과 동일하게 불일치 시 바로 에러.
     */
    private String decideLevel(BigDecimal a, BigDecimal b, BigDecimal toleranceRatio) {
        BigDecimal diff = a.subtract(b).abs();
        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            return AnalyzeConstants.LEVEL_NORMAL;
        }

        if (toleranceRatio != null && toleranceRatio.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal allowedDiff = b.abs()
                    .multiply(toleranceRatio)
                    .divide(ONE_HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);
            if (diff.compareTo(allowedDiff) <= 0) {
                return AnalyzeConstants.LEVEL_WARNING;
            }
        }

        return AnalyzeConstants.LEVEL_ERROR;
    }

    /**
     * 연산자 기반 2단계(정상/에러) 판정 — B(로그의 두 번째 값)를 동적 임계치로 삼아
     * A op (B ± 허용오차%) 조건 충족 여부만 본다. 경고 등급 없음(3절 6-4 참고).
     * 허용오차 0이면 순수 A op B와 동일.
     */
    private String decideLevelByOperator(BigDecimal a, BigDecimal b, String operator, BigDecimal toleranceRatio) {
        BigDecimal delta = b.abs()
                .multiply(toleranceRatio)
                .divide(ONE_HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);

        boolean normal;
        if (">=".equals(operator)) {
            normal = a.compareTo(b.subtract(delta)) >= 0;
        } else if (">".equals(operator)) {
            normal = a.compareTo(b.subtract(delta)) > 0;
        } else if ("<=".equals(operator)) {
            normal = a.compareTo(b.add(delta)) <= 0;
        } else if ("<".equals(operator)) {
            normal = a.compareTo(b.add(delta)) < 0;
        } else {
            throw new AnalyzeException("지원하지 않는 비교형 연산자: " + operator);
        }

        return normal ? AnalyzeConstants.LEVEL_NORMAL : AnalyzeConstants.LEVEL_ERROR;
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

    private String buildMessage(String level, String logId, String content, BigDecimal a, BigDecimal b, BigDecimal toleranceRatio, String operator) {
        String aText = a.stripTrailingZeros().toPlainString();
        String bText = b.stripTrailingZeros().toPlainString();

        if (operator != null) {
            String ratioText = toleranceRatio.stripTrailingZeros().toPlainString();
            if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
                return "[" + level + "][" + logId + "] " + content + " (A=" + aText + ", B=" + bText
                        + ", A " + operator + " B 조건 불충족 - 허용오차 " + ratioText + "% 반영)";
            }
            return "[" + level + "][" + logId + "] " + content + " (A=" + aText + ", B=" + bText
                    + ", A " + operator + " B 조건 충족 - 허용오차 " + ratioText + "% 반영)";
        }

        if (AnalyzeConstants.LEVEL_WARNING.equals(level)) {
            return "[" + level + "][" + logId + "] " + content + " (A=" + aText + ", B=" + bText
                    + ", 불일치하나 B 대비 허용값 " + toleranceRatio.stripTrailingZeros().toPlainString() + "% 이내 근접)";
        }
        if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
            return "[" + level + "][" + logId + "] " + content + " (A=" + aText + ", B=" + bText + ", 불일치)";
        }
        return "[" + level + "][" + logId + "] " + content + " (A=" + aText + ", B=" + bText + ", 일치)";
    }

    private ParsedTwoNumbers parseTwoNumbers(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        Matcher matcher = DOLLAR_PATTERN.matcher(content);
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

        if (candidates.size() != 2) {
            return null;
        }

        try {
            BigDecimal a = new BigDecimal(candidates.get(0));
            BigDecimal b = new BigDecimal(candidates.get(1));
            return new ParsedTwoNumbers(a, b);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractContentWithTokens(CollectLog log) {
        if (log == null) {
            return null;
        }

        String content = log.getLogContent();
        if (content != null && content.contains("$")) {
            return content;
        }

        String rawLog = log.getRawLog();
        String rawContent = extractContentFromRawLog(rawLog);
        if (rawContent != null && rawContent.contains("$")) {
            return rawContent;
        }

        return content;
    }

    private String extractContentFromRawLog(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return null;
        }

        int firstPipe = rawLog.indexOf('|');
        if (firstPipe < 0) {
            return null;
        }
        int secondPipe = rawLog.indexOf('|', firstPipe + 1);
        if (secondPipe < 0) {
            return null;
        }
        return rawLog.substring(firstPipe + 1, secondPipe);
    }

    private static class ParsedTwoNumbers {
        private final BigDecimal a;
        private final BigDecimal b;

        private ParsedTwoNumbers(BigDecimal a, BigDecimal b) {
            this.a = a;
            this.b = b;
        }
    }
}

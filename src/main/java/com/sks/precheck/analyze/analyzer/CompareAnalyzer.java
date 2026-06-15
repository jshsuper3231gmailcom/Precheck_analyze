package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CompareAnalyzer implements LogAnalyzer {

    private static final Pattern DOLLAR_PATTERN = Pattern.compile("\\$([^$]+)\\$");

    @Override
    public AnalyzeResult analyze(CollectLog log, AnalyzePolicy policy) {
        if (!"ComparePolicy".equals(policy.getClass().getSimpleName())) {
            throw new AnalyzeException("비교형 정책이 아니다 - serverId: " + log.getServerId() + ", logId: " + log.getLogId()
                    + ", 수집로그타입: " + log.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        String contentWithTokens = extractContentWithTokens(log);
        ParsedTwoNumbers parsed = parseTwoNumbers(contentWithTokens);
        if (parsed == null) {
            AnalyzeResult result = baseResult(log);
            result.setAnalyzeLevel(AnalyzeConstants.LEVEL_UNANALYZED);
            result.setAnalyzeMessage("[미분석][" + log.getLogId() + "] 포맷 불일치");
            return result;
        }

        boolean matched = parsed.a.compareTo(parsed.b) == 0;
        String level = matched ? AnalyzeConstants.LEVEL_NORMAL : AnalyzeConstants.LEVEL_ERROR;

        AnalyzeResult result = baseResult(log);
        result.setAnalyzeLevel(level);
        result.setAnalyzeMessage(buildMessage(level, log.getLogId(), log.getLogContent(), parsed.a, parsed.b));
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

    private String buildMessage(String level, String logId, String content, BigDecimal a, BigDecimal b) {
        if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
            return "[" + level + "][" + logId + "] " + content + " (A=" + a.stripTrailingZeros().toPlainString()
                    + ", B=" + b.stripTrailingZeros().toPlainString() + ", 불일치)";
        }
        return "[" + level + "][" + logId + "] " + content + " (A=" + a.stripTrailingZeros().toPlainString()
                + ", B=" + b.stripTrailingZeros().toPlainString() + ", 일치)";
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

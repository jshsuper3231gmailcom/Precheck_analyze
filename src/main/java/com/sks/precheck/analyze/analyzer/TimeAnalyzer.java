package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.TimePolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TimeAnalyzer implements LogAnalyzer {

    private static final Pattern DOLLAR_PATTERN = Pattern.compile("\\$([^$]+)\\$");

    @Override
    public AnalyzeResult analyze(CollectLog log, AnalyzePolicy policy) {
        if (!(policy instanceof TimePolicy)) {
            throw new AnalyzeException("시간형 정책이 아니다 - serverId: " + log.getServerId() + ", logId: " + log.getLogId()
                    + ", 수집로그타입: " + log.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        TimePolicy timePolicy = (TimePolicy) policy;
        String contentWithTokens = extractContentWithTokens(log);
        String timeText = parseSingleTime(contentWithTokens);
        if (timeText == null) {
            AnalyzeResult result = baseResult(log);
            result.setAnalyzeLevel(AnalyzeConstants.LEVEL_UNANALYZED);
            result.setAnalyzeMessage("[미분석][" + log.getLogId() + "] 포맷 불일치");
            return result;
        }

        int logMinutes = toMinutes(timeText);
        int thresholdMinutes = toMinutes(timePolicy.getThresholdTime());
        String operator = timePolicy.getOperator();

        boolean ok = compare(logMinutes, operator, thresholdMinutes);
        String level = ok ? AnalyzeConstants.LEVEL_NORMAL : AnalyzeConstants.LEVEL_ERROR;

        AnalyzeResult result = baseResult(log);
        result.setAnalyzeLevel(level);
        result.setLogValue(BigDecimal.valueOf(logMinutes));
        result.setThresholdValue(BigDecimal.valueOf(thresholdMinutes));
        result.setThresholdOperator(operator);
        result.setAnalyzeMessage(buildMessage(level, log.getLogId(), log.getLogContent(), timeText, operator, timePolicy.getThresholdTime()));
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

    private String buildMessage(String level, String logId, String content, String timeText, String operator, String thresholdTime) {
        if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
            return "[" + level + "][" + logId + "] " + content + " " + timeText + " " + oppositeOperator(operator) + " " + thresholdTime + "(임계치)";
        }
        return "[" + level + "][" + logId + "] " + content + " " + timeText + " " + operator + " " + thresholdTime + "(임계치)";
    }

    private String parseSingleTime(String content) {
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
            if (!trimmed.contains(":")) {
                continue;
            }
            if (!isValidTimeHhmm(trimmed)) {
                return null;
            }
            candidates.add(trimmed);
        }

        if (candidates.size() != 1) {
            return null;
        }
        return candidates.get(0);
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

    private int toMinutes(String hhmm) {
        if (!isValidTimeHhmm(hhmm)) {
            throw new AnalyzeException("시간 포맷 오류(HH:mm): " + hhmm);
        }
        int hh = Integer.parseInt(hhmm.substring(0, 2));
        int mm = Integer.parseInt(hhmm.substring(3, 5));
        return hh * 60 + mm;
    }

    private boolean compare(int leftMinutes, String operator, int rightMinutes) {
        if ("<".equals(operator)) {
            return leftMinutes < rightMinutes;
        }
        if ("<=".equals(operator)) {
            return leftMinutes <= rightMinutes;
        }
        if (">".equals(operator)) {
            return leftMinutes > rightMinutes;
        }
        if (">=".equals(operator)) {
            return leftMinutes >= rightMinutes;
        }
        throw new AnalyzeException("지원하지 않는 연산자: " + operator);
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
        return operator;
    }
}

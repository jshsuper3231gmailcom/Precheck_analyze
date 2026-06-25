package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.DatePolicy;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 날짜형 로그 분석기 — 로그 내 $yyyy/MM/dd$ 또는 $yyyy-MM-dd$ 값 토큰이 오늘과 일치하면 LEVEL_NORMAL, 다르면 LEVEL_ERROR
 *
 * 날짜 값은 수치형/시간형과 동일하게 $...$ 토큰으로 감싸여 있다(collect 단계에서 토큰 존재·날짜 유효성 검증 완료).
 */
@Component
public class DateAnalyzer implements LogAnalyzer {

    private static final DateTimeFormatter LOG_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Pattern VALUE_TOKEN_PATTERN = Pattern.compile("\\$([^$]+)\\$");

    @Override
    public AnalyzeResult analyze(CollectLog log, AnalyzePolicy policy) {
        if (!(policy instanceof DatePolicy)) {
            throw new AnalyzeException("날짜형 정책이 아니다 - serverId: " + log.getServerId() + ", logId: " + log.getLogId()
                    + ", 수집로그타입: " + log.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        String today = LocalDate.now().format(LOG_DATE_FORMATTER);
        List<String> dates = extractDates(log.getLogContent());

        String mismatched = findFirstMismatch(dates, today);
        String level = mismatched == null ? AnalyzeConstants.LEVEL_NORMAL : AnalyzeConstants.LEVEL_ERROR;

        AnalyzeResult result = baseResult(log);
        result.setAnalyzeLevel(level);
        result.setAnalyzeMessage(buildMessage(level, log.getLogId(), log.getLogContent(), today, mismatched));
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

    private List<String> extractDates(String content) {
        List<String> dates = new ArrayList<>();
        if (content == null) {
            return dates;
        }

        Matcher matcher = VALUE_TOKEN_PATTERN.matcher(content);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isEmpty()) {
                dates.add(value);
            }
        }
        return dates;
    }

    private String findFirstMismatch(List<String> dates, String today) {
        if (dates == null || dates.isEmpty()) {
            return "없음";
        }
        for (String date : dates) {
            if (date != null && !normalizeSeparator(date).equals(today)) {
                return date;
            }
        }
        return null;
    }

    private String normalizeSeparator(String date) {
        return date.replace('-', '/');
    }

    private String buildMessage(String level, String logId, String content, String today, String mismatched) {
        if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
            return "[" + level + "][" + logId + "] " + content + " (날짜 불일치: 기대=" + today + ", 실제=" + mismatched + ")";
        }
        return "[" + level + "][" + logId + "] " + content + " (오늘 날짜 일치)";
    }
}

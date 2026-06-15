package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.PhrasePolicy;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 문구형 로그 분석기 — 에러 키워드 포함 여부로 LEVEL_ERROR / LEVEL_NORMAL 판정
 */
@Component
public class PhraseAnalyzer implements LogAnalyzer {

    private static final Logger log = LogManager.getLogger(PhraseAnalyzer.class);

    @Override
    public AnalyzeResult analyze(CollectLog collectLog, AnalyzePolicy policy) {
        if (!(policy instanceof PhrasePolicy)) {
            throw new AnalyzeException("문구형 정책이 아니다 - serverId: " + collectLog.getServerId() + ", logId: " + collectLog.getLogId()
                    + ", 수집로그타입: " + collectLog.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        PhrasePolicy phrasePolicy = (PhrasePolicy) policy;
        String logContent = collectLog.getLogContent();

        String matchedKeyword = findMatchedKeyword(logContent, phrasePolicy.getErrorKeywords());
        String level = matchedKeyword == null ? AnalyzeConstants.LEVEL_NORMAL : AnalyzeConstants.LEVEL_ERROR;

        log.debug("Analyzing logId={}, matchedKeyword={}, level={}", collectLog.getLogId(), matchedKeyword, level);

        AnalyzeResult result = baseResult(collectLog);
        result.setAnalyzeLevel(level);
        result.setAnalyzeMessage(buildMessage(level, collectLog.getLogId(), logContent, matchedKeyword));
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

    private String findMatchedKeyword(String content, List<String> keywords) {
        if (content == null || keywords == null || keywords.isEmpty()) {
            return null;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && content.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private String buildMessage(String level, String logId, String content, String matchedKeyword) {
        if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
            return "[" + level + "][" + logId + "] " + content + " (키워드: " + matchedKeyword + ")";
        }
        return "[" + level + "][" + logId + "] " + content;
    }
}

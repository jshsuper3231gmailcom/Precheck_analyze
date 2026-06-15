package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.ExistencePolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 존재형 로그 분석기 — 수집 로그 존재 자체가 대상 파일/프로세스 부재를 의미하므로 항상 LEVEL_ERROR
 */
@Component
public class ExistenceAnalyzer implements LogAnalyzer {

    private static final Logger log = LogManager.getLogger(ExistenceAnalyzer.class);

    @Override
    public AnalyzeResult analyze(CollectLog collectLog, AnalyzePolicy policy) {
        log.debug("ExistenceAnalyzer.analyze called for collectLogId={}", collectLog != null ? collectLog.getCollectLogId() : null);

        if (!(policy instanceof ExistencePolicy)) {
            throw new AnalyzeException("존재형 정책이 아니다 - serverId: " + collectLog.getServerId() + ", logId: " + collectLog.getLogId()
                    + ", 수집로그타입: " + collectLog.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        if (collectLog == null) {
            throw new AnalyzeException("collectLog is null");
        }

        AnalyzeResult result = baseResult(collectLog);
        result.setAnalyzeLevel(AnalyzeConstants.LEVEL_ERROR);
        result.setAnalyzeMessage("[" + AnalyzeConstants.LEVEL_ERROR + "][" + collectLog.getLogId() + "] " + collectLog.getLogContent());
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
}

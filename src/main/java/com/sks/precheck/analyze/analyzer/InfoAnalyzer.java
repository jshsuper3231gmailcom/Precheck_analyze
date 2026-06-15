package com.sks.precheck.analyze.analyzer;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.domain.policy.InfoPolicy;
import org.springframework.stereotype.Component;

/**
 * 정보형 로그 분석기 — 분석 없이 항상 LEVEL_INFO 저장 (통보 서버에서 제외되는 조회 전용 타입)
 */
@Component
public class InfoAnalyzer implements LogAnalyzer {

    @Override
    public AnalyzeResult analyze(CollectLog log, AnalyzePolicy policy) {
        if (!(policy instanceof InfoPolicy)) {
            throw new AnalyzeException("정보형 정책이 아니다 - serverId: " + log.getServerId() + ", logId: " + log.getLogId()
                    + ", 수집로그타입: " + log.getLogType() + ", 정책타입: " + policy.getLogType() + "(" + policy.getClass().getSimpleName() + ")");
        }

        AnalyzeResult result = baseResult(log);
        result.setAnalyzeLevel(AnalyzeConstants.LEVEL_INFO);
        result.setAnalyzeMessage("[" + AnalyzeConstants.LEVEL_INFO + "][" + log.getLogId() + "] " + log.getLogContent());
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

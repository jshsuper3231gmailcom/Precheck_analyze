package com.sks.precheck.analyze.service;

import com.sks.precheck.analyze.analyzer.CompareAnalyzer;
import com.sks.precheck.analyze.analyzer.DateAnalyzer;
import com.sks.precheck.analyze.analyzer.ExistenceAnalyzer;
import com.sks.precheck.analyze.analyzer.InfoAnalyzer;
import com.sks.precheck.analyze.analyzer.NumericAnalyzer;
import com.sks.precheck.analyze.analyzer.PhraseAnalyzer;
import com.sks.precheck.analyze.analyzer.TimeAnalyzer;
import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import com.sks.precheck.analyze.common.exception.AnalyzeException;
import com.sks.precheck.analyze.common.util.SequenceHelper;
import com.sks.precheck.analyze.config.PolicyLoader;
import com.sks.precheck.analyze.domain.AnalyzeHistory;
import com.sks.precheck.analyze.domain.AnalyzeResult;
import com.sks.precheck.analyze.domain.CollectLog;
import com.sks.precheck.analyze.domain.policy.AnalyzePolicy;
import com.sks.precheck.analyze.mapper.AnalyzeHistoryMapper;
import com.sks.precheck.analyze.mapper.AnalyzeResultMapper;
import com.sks.precheck.analyze.mapper.CollectLogMapper;
import com.sks.precheck.analyze.vo.AnalyzeScheduleVo;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 실제 분석 수행 서비스
 *
 * AnalyzeException 발생 시 @Retryable이 10초 간격으로 최대 3회 재시도한다.
 * maxAttempts=4 = 최초 1회 + 재시도 3회. 모두 실패하면 @Recover가 이력을 FAIL로 마감한다.
 *
 * 분석 대상 로그 조회 방식:
 * - 배치: 오늘 날짜의 전체 로그(selectForAnalyze)
 * - 주기: 마지막 성공 이력의 lastAnalyzeLogId 이후 신규 로그만(selectAfterLogId) — 중복 분석 방지
 */
@Service
public class AnalyzeRetryService {

    private static final Logger log = LogManager.getLogger(AnalyzeRetryService.class);

    private final SequenceHelper sequenceHelper;
    private final CollectLogMapper collectLogMapper;
    private final AnalyzeResultMapper analyzeResultMapper;
    private final AnalyzeHistoryMapper analyzeHistoryMapper;
    private final PolicyLoader policyLoader;

    private final PhraseAnalyzer phraseAnalyzer;
    private final NumericAnalyzer numericAnalyzer;
    private final DateAnalyzer dateAnalyzer;
    private final ExistenceAnalyzer existenceAnalyzer;
    private final InfoAnalyzer infoAnalyzer;
    private final CompareAnalyzer compareAnalyzer;
    private final TimeAnalyzer timeAnalyzer;

    public AnalyzeRetryService(
            SequenceHelper sequenceHelper,
            CollectLogMapper collectLogMapper,
            AnalyzeResultMapper analyzeResultMapper,
            AnalyzeHistoryMapper analyzeHistoryMapper,
            PolicyLoader policyLoader,
            PhraseAnalyzer phraseAnalyzer,
            NumericAnalyzer numericAnalyzer,
            DateAnalyzer dateAnalyzer,
            ExistenceAnalyzer existenceAnalyzer,
            InfoAnalyzer infoAnalyzer,
            CompareAnalyzer compareAnalyzer,
            TimeAnalyzer timeAnalyzer
    ) {
        this.sequenceHelper = sequenceHelper;
        this.collectLogMapper = collectLogMapper;
        this.analyzeResultMapper = analyzeResultMapper;
        this.analyzeHistoryMapper = analyzeHistoryMapper;
        this.policyLoader = policyLoader;
        this.phraseAnalyzer = phraseAnalyzer;
        this.numericAnalyzer = numericAnalyzer;
        this.dateAnalyzer = dateAnalyzer;
        this.existenceAnalyzer = existenceAnalyzer;
        this.infoAnalyzer = infoAnalyzer;
        this.compareAnalyzer = compareAnalyzer;
        this.timeAnalyzer = timeAnalyzer;
    }

    @Retryable(
            retryFor = {AnalyzeException.class},
            maxAttemptsExpression = "#{T(com.sks.precheck.analyze.common.constants.AnalyzeConstants).MAX_RETRY_COUNT + 1}",
            backoff = @Backoff(delayExpression = "#{T(com.sks.precheck.analyze.common.constants.AnalyzeConstants).RETRY_DELAY_MILLISECONDS}")
    )
    public int analyzeWithRetry(
            Long historyId,
            AnalyzeScheduleVo scheduleVo,
            String scheduleType,
            String analyzeTargetDate,
            String analyzeDate
    ) {
        try {
            return analyzeInternal(historyId, scheduleVo, scheduleType, analyzeTargetDate, analyzeDate);
        } catch (AnalyzeException e) {
            log.error("분석 처리 실패(재시도 예정) - 서버: {}, 타입: {}, 대상일: {}",
                    scheduleVo != null ? scheduleVo.getServerId() : null,
                    scheduleType, analyzeTargetDate, e);
            throw e;
        } catch (Exception e) {
            log.error("분석 처리 중 예외(재시도 예정) - 서버: {}, 타입: {}, 대상일: {}",
                    scheduleVo != null ? scheduleVo.getServerId() : null,
                    scheduleType, analyzeTargetDate, e);
            throw new AnalyzeException("분석 처리 실패", e);
        }
    }

    private int analyzeInternal(
            Long historyId,
            AnalyzeScheduleVo scheduleVo,
            String scheduleType,
            String analyzeTargetDate,
            String analyzeDate
    ) {
        String serverId = scheduleVo.getServerId();
        String sourceFilePath = scheduleVo.getSourceFilePath();

        // 주기 스케줄: 마지막 성공 이력에서 lastAnalyzeLogId를 읽어 해당 이후 로그만 조회
        // 이력이 없거나 배치 스케줄이면 오늘 전체 조회(selectForAnalyze)로 fallback
        Long lastAnalyzeLogId = null;
        if ("주기".equals(scheduleType)) {
            // analyzeTargetDate(당일)로 범위를 한정 — 전날 이력의 lastAnalyzeLogId가 섞이면 오늘 로그를 건너뜀
            AnalyzeHistory lastSuccess = analyzeHistoryMapper.selectLastSuccess(serverId, sourceFilePath, analyzeTargetDate);
            if (lastSuccess != null) {
                lastAnalyzeLogId = lastSuccess.getLastAnalyzeLogId();
            }
        }

        List<CollectLog> logs;
        if ("주기".equals(scheduleType) && lastAnalyzeLogId != null) {
            logs = collectLogMapper.selectAfterLogId(analyzeTargetDate, serverId, sourceFilePath, lastAnalyzeLogId);
        } else {
            logs = collectLogMapper.selectForAnalyze(analyzeTargetDate, serverId, sourceFilePath);
        }

        long errorCount = 0;
        long warningCount = 0;

        Long lastProcessedLogId = lastAnalyzeLogId;
        long successCount = 0;

        for (CollectLog collectLog : logs) {
            AnalyzeResult result;
            try {
                result = analyzeOne(collectLog);
            } catch (AnalyzeException e) {
                // 정책타입불일치/필수값누락/포맷오류 등 데이터·설정 문제 - 배치 중단 없이 미분석 처리 후 계속 진행
                log.warn("[스킵] 미분석 처리 - serverId: {}, serverIp: {}, collectLogId: {}, logId: {}, logType: {}, 사유: {}",
                        serverId, collectLog.getServerIp(),
                        collectLog.getCollectLogId(), collectLog.getLogId(),
                        collectLog.getLogType(), e.getMessage());
                result = buildUnanalyzedResult(collectLog, e.getMessage());
            } catch (Exception e) {
                log.error("[예외] 분석 실패 - serverId: {}, serverIp: {}, collectLogId: {}, logId: {}, logType: {}, logContent: {}",
                        serverId, collectLog.getServerIp(),
                        collectLog.getCollectLogId(), collectLog.getLogId(),
                        collectLog.getLogType(), collectLog.getLogContent(), e);
                throw e;
            }

            LocalDateTime now = LocalDateTime.now();
            result.setAnalyzeResultId(sequenceHelper.nextval("SEQ_ANALYZE_RESULT"));
            result.setAnalyzeDate(analyzeDate);
            result.setAnalyzeDatetime(now);
            result.setCollectDate(collectLog.getCollectDate());
            result.setNotifyYn("N");
            result.setCreatedAt(now);

            try {
                analyzeResultMapper.insert(result);
            } catch (Exception e) {
                log.error("[예외] 결과 저장 실패 - serverId: {}, serverIp: {}, collectLogId: {}, logId: {}, logType: {}",
                        serverId, collectLog.getServerIp(),
                        collectLog.getCollectLogId(), collectLog.getLogId(),
                        collectLog.getLogType(), e);
                throw e;
            }

            String level = result.getAnalyzeLevel();
            if (AnalyzeConstants.LEVEL_ERROR.equals(level)) {
                errorCount++;
                log.warn("[에러] serverId: {}, serverIp: {}, collectLogId: {}, logId: {}, logType: {}, 판정: {}",
                        serverId, collectLog.getServerIp(),
                        collectLog.getCollectLogId(), collectLog.getLogId(),
                        collectLog.getLogType(), result.getAnalyzeMessage());
            } else if (AnalyzeConstants.LEVEL_WARNING.equals(level)) {
                warningCount++;
                log.warn("[경고] serverId: {}, serverIp: {}, collectLogId: {}, logId: {}, logType: {}, 판정: {}",
                        serverId, collectLog.getServerIp(),
                        collectLog.getCollectLogId(), collectLog.getLogId(),
                        collectLog.getLogType(), result.getAnalyzeMessage());
            } else {
                log.info("[{}] serverId: {}, serverIp: {}, collectLogId: {}, logId: {}, logType: {}, 판정: {}",
                        level, serverId, collectLog.getServerIp(),
                        collectLog.getCollectLogId(), collectLog.getLogId(),
                        collectLog.getLogType(), result.getAnalyzeMessage());
            }

            lastProcessedLogId = collectLog.getCollectLogId();
            successCount++;
        }

        AnalyzeHistory update = new AnalyzeHistory();
        update.setAnalyzeHistoryId(historyId);
        update.setAnalyzeStatus(AnalyzeConstants.STATUS_SUCCESS);
        update.setLastAnalyzeLogId(lastProcessedLogId);
        update.setTotalCount((long) logs.size());
        update.setSuccessCount(successCount);
        update.setFailCount(0L);
        update.setErrorCount(errorCount);
        update.setWarningCount(warningCount);
        update.setFailReason(null);
        update.setAnalyzeEndAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        analyzeHistoryMapper.update(update);

        log.info("분석 완료 - 서버: {}, 타입: {}, 대상일: {}, 총: {}건, 에러: {}건, 경고: {}건",
                serverId, scheduleType, analyzeTargetDate, logs.size(), errorCount, warningCount);

        return logs.size();
    }

    @Recover
    public int recover(
            AnalyzeException e,
            Long historyId,
            AnalyzeScheduleVo scheduleVo,
            String scheduleType,
            String analyzeTargetDate,
            String analyzeDate
    ) {
        AnalyzeHistory update = new AnalyzeHistory();
        update.setAnalyzeHistoryId(historyId);
        update.setAnalyzeStatus(AnalyzeConstants.STATUS_FAIL);
        update.setFailReason(e.getMessage());
        update.setAnalyzeEndAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        analyzeHistoryMapper.update(update);

        log.error("분석 재시도 모두 실패 - 서버: {}, 타입: {}, 대상일: {}",
                scheduleVo != null ? scheduleVo.getServerId() : null,
                scheduleType,
                analyzeTargetDate,
                e);

        return 0;
    }

    private AnalyzeResult analyzeOne(CollectLog logRow) {
        AnalyzePolicy policy = policyLoader.findPolicy(logRow.getServerId(), logRow.getLogId());
        if (policy == null) {
            return buildUnanalyzedResult(logRow);
        }

        String logType = logRow.getLogType();
        if (AnalyzeConstants.LOG_TYPE_TEXT.equals(logType)) {
            return phraseAnalyzer.analyze(logRow, policy);
        }
        if (AnalyzeConstants.LOG_TYPE_NUMERIC.equals(logType)) {
            return numericAnalyzer.analyze(logRow, policy);
        }
        if (AnalyzeConstants.LOG_TYPE_DATE.equals(logType)) {
            return dateAnalyzer.analyze(logRow, policy);
        }
        if (AnalyzeConstants.LOG_TYPE_EXIST.equals(logType)) {
            return existenceAnalyzer.analyze(logRow, policy);
        }
        if (AnalyzeConstants.LOG_TYPE_INFO.equals(logType)) {
            return infoAnalyzer.analyze(logRow, policy);
        }
        if (AnalyzeConstants.LOG_TYPE_COMPARE.equals(logType)) {
            return compareAnalyzer.analyze(logRow, policy);
        }
        if (AnalyzeConstants.LOG_TYPE_TIME.equals(logType)) {
            return timeAnalyzer.analyze(logRow, policy);
        }

        return buildUnanalyzedResult(logRow);
    }

    private AnalyzeResult buildUnanalyzedResult(CollectLog logRow) {
        AnalyzeResult result = new AnalyzeResult();
        result.setCollectLogId(logRow.getCollectLogId());
        result.setServerId(logRow.getServerId());
        result.setServerIp(logRow.getServerIp());
        result.setLogType(logRow.getLogType());
        result.setLogId(logRow.getLogId());
        result.setLogTimestamp(logRow.getLogTimestamp());
        result.setLogContent(logRow.getLogContent());
        result.setLogValue(logRow.getLogValue());
        result.setAnalyzeLevel(AnalyzeConstants.LEVEL_UNANALYZED);
        result.setAnalyzeMessage("[미분석][" + logRow.getLogId() + "] 분석 정책 미등록");
        return result;
    }

    private AnalyzeResult buildUnanalyzedResult(CollectLog logRow, String reason) {
        AnalyzeResult result = buildUnanalyzedResult(logRow);
        result.setAnalyzeMessage("[미분석][" + logRow.getLogId() + "] " + reason);
        return result;
    }
}

package com.sks.precheck.analyze.domain.policy;

import com.sks.precheck.analyze.common.constants.AnalyzeConstants;
import java.math.BigDecimal;

public class ComparePolicy implements AnalyzePolicy {

    private String serverId;
    private String logId;
    private final String logType = AnalyzeConstants.LOG_TYPE_COMPARE;
    private BigDecimal toleranceRatio = BigDecimal.ZERO;
    /** null이면 기존 대칭모드(A==B만 정상), 지정되면 연산자 기반 2단계(정상/에러) 모드 — B를 동적 임계치로 사용 */
    private String operator;

    @Override
    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    @Override
    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    @Override
    public String getLogType() {
        return logType;
    }

    public BigDecimal getToleranceRatio() {
        return toleranceRatio;
    }

    public void setToleranceRatio(BigDecimal toleranceRatio) {
        this.toleranceRatio = toleranceRatio;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}


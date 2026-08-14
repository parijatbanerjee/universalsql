package com.ema.usql.planner;

import com.ema.usql.sourcegateway.api.RateLimitStatus;
import com.ema.usql.sourcegateway.api.SourceGateway;

/**
 * RateLimitBudget backed by the SourceGateway's getRateLimitStatus query.
 * A connector is considered exhausted if its remaining tokens reach 0.
 */
public class RateLimitBudgetImpl implements RateLimitBudget {

    private final SourceGateway sourceGateway;
    private final String tenantId;

    public RateLimitBudgetImpl(SourceGateway sourceGateway, String tenantId) {
        this.sourceGateway = sourceGateway;
        this.tenantId = tenantId;
    }

    @Override
    public boolean exhaustedFor(String connector) {
        try {
            RateLimitStatus status = sourceGateway.getRateLimitStatus(connector, tenantId);
            return status.remaining() <= 0;
        } catch (Exception e) {
            // Fail open on budget check errors (conservative: assume not exhausted)
            return false;
        }
    }
}

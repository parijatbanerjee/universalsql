package com.ema.usql.shared;

import java.util.List;
import java.util.Map;

/**
 * The physical query plan produced by the planner, ready for execution by the coordinator.
 */
public record PhysicalPlan(
        String planId,
        List<Fragment> fragments,
        JoinStrategy joinStrategy,
        String rls,
        Map<String, String> clsMasks
) {
}

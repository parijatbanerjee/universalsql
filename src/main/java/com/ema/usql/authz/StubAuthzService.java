package com.ema.usql.authz;

import com.ema.usql.authz.api.AuthzContext;
import com.ema.usql.authz.api.AuthzService;
import com.ema.usql.authz.api.ClsMaskSet;
import com.ema.usql.authz.api.RlsPredicate;
import com.ema.usql.shared.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Permissive stub AuthzService used until Task 10 implements the real authorization logic.
 * Returns an AuthzContext with no RLS restriction and no CLS masking.
 *
 * <p>This bean is only registered when no other {@link AuthzService} bean is present,
 * so Task 10 can override it by providing a {@code @Primary} or replacement bean.
 */
@Service
@ConditionalOnMissingBean(value = AuthzService.class, ignored = StubAuthzService.class)
public class StubAuthzService implements AuthzService {

    @Override
    public AuthzContext resolve(TenantContext tenantContext) {
        return new AuthzContext(
                Set.of(tenantContext.userId()),
                new RlsPredicate(null),
                new ClsMaskSet(Map.of()),
                0L,
                Instant.EPOCH
        );
    }
}

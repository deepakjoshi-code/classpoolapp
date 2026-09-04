package app.classpool.api.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately not a JPA entity/repository: Phase 1-2 has no pool-creation endpoint (see
 * ARCHITECTURE.md §4 / PRD §17.3 build order) so `pool` is a Phase 3+ table by that guidance.
 * But the late-join rule (PRD §13.3 update) needs to read pool state at join time regardless of
 * which phase created the row, so this is a narrow, read-only JdbcTemplate query against the
 * table rather than a full entity mapping.
 */
@Component
public class PoolGateway {

    /** Pool states PRD §13.3 already treats as "left OPEN_FOR_CONTRIBUTIONS". */
    private static final List<String> PAST_OPEN_FOR_CONTRIBUTIONS = List.of(
            "RECONCILING", "PURCHASE_PROPOSED", "PAYMENT_OPEN", "ORDERED", "DISTRIBUTING", "COMPLETED"
    );

    private final JdbcTemplate jdbcTemplate;

    public PoolGateway(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * True if this classroom has at least one pool that has already left
     * OPEN_FOR_CONTRIBUTIONS — the trigger for Membership.lateJoin = true (PRD §13.3 update).
     * Phase 1-2 has no pool-creation endpoint, so this is often moot (no rows yet, returns
     * false) — but the check is written correctly for when Phase 3+ starts creating pools.
     */
    public boolean hasPoolPastOpenForContributions(UUID classroomId) {
        String sql = "select count(*) from pool where classroom_id = ? and state in ("
                + String.join(",", PAST_OPEN_FOR_CONTRIBUTIONS.stream().map(s -> "?").toList())
                + ")";
        Object[] args = new Object[1 + PAST_OPEN_FOR_CONTRIBUTIONS.size()];
        args[0] = classroomId;
        for (int i = 0; i < PAST_OPEN_FOR_CONTRIBUTIONS.size(); i++) {
            args[i + 1] = PAST_OPEN_FOR_CONTRIBUTIONS.get(i);
        }
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }
}

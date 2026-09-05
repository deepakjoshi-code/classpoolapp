package app.classpool.api.repository;

import app.classpool.api.domain.RequirementSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RequirementSourceRepository extends JpaRepository<RequirementSource, UUID> {

    /** Every import attempt for a pool, oldest first (contract: "an audit trail of what was
     *  pasted and when"). */
    List<RequirementSource> findByPoolIdOrderByCreatedAtAsc(UUID poolId);
}

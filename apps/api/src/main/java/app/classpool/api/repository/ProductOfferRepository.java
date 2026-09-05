package app.classpool.api.repository;

import app.classpool.api.domain.ProductOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductOfferRepository extends JpaRepository<ProductOffer, UUID> {

    /** Candidate offers for one requirement — the DP optimizer's input for that requirement. */
    List<ProductOffer> findByRequirementIdOrderByCreatedAtAsc(UUID requirementId);

    /** Every offer across a pool's requirements (GET /pools/{poolId}/product-offers), batched over
     *  requirement ids same as {@code ContributionRepository.findByRequirementIdInOrderByCreatedAtAsc}. */
    List<ProductOffer> findByRequirementIdInOrderByCreatedAtAsc(Collection<UUID> requirementIds);

    /** Scoped fetch: only returns a hit if {@code offerId} belongs to one of this pool's
     *  requirements, matching {@code ContributionRepository.findByIdAndRequirementIdIn}'s
     *  cross-tenant guard. */
    Optional<ProductOffer> findByIdAndRequirementIdIn(UUID id, Collection<UUID> requirementIds);
}

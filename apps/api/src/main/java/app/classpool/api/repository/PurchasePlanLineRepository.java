package app.classpool.api.repository;

import app.classpool.api.domain.PurchasePlanLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PurchasePlanLineRepository extends JpaRepository<PurchasePlanLine, UUID> {

    List<PurchasePlanLine> findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(UUID purchasePlanId);
}

package app.classpool.api.repository;

import app.classpool.api.domain.Household;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {
    Optional<Household> findByPrimaryParentId(UUID primaryParentId);
}

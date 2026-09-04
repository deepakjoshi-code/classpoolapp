package app.classpool.api.repository;

import app.classpool.api.domain.SchoolYear;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SchoolYearRepository extends JpaRepository<SchoolYear, UUID> {
    Optional<SchoolYear> findBySchoolIdAndLabel(UUID schoolId, String label);
}

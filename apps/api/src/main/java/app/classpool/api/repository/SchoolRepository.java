package app.classpool.api.repository;

import app.classpool.api.domain.School;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SchoolRepository extends JpaRepository<School, UUID> {

    /**
     * Fuzzy match against existing schools (PRD §2.3 dedup-check-before-create flow).
     * Backed by the pg_trgm GIN index on school.name (idx_school_name_trgm, V1 migration).
     * Threshold 0.3 is pg_trgm's own conventional default for "meaningfully similar".
     */
    @Query(value = """
            select * from school
            where similarity(name, :q) > 0.3
            order by similarity(name, :q) desc
            limit 10
            """, nativeQuery = true)
    List<School> fuzzySearch(@Param("q") String q);
}

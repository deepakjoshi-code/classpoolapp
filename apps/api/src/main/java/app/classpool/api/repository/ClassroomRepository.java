package app.classpool.api.repository;

import app.classpool.api.domain.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {

    /**
     * Fuzzy match against existing classrooms in the same school year (PRD §2.3 dedup: two
     * parents at the same school both starting "Lincoln Elementary, Ms. Smith, Grade 1" as
     * separate classes, unaware of each other). Backed by the pg_trgm GIN index on
     * classroom (grade || ' ' || teacher_label) (idx_classroom_grade_teacher_trgm, V1 migration).
     */
    @Query(value = """
            select c.* from classroom c
            where c.school_year_id = :schoolYearId
              and similarity(c.grade || ' ' || c.teacher_label, :q) > 0.3
            order by similarity(c.grade || ' ' || c.teacher_label, :q) desc
            limit 10
            """, nativeQuery = true)
    List<Classroom> fuzzySearchInSchoolYear(@Param("schoolYearId") UUID schoolYearId, @Param("q") String q);
}

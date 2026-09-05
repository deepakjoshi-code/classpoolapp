package app.classpool.api.repository;

import app.classpool.api.domain.ClassReserveEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClassReserveEntryRepository extends JpaRepository<ClassReserveEntry, UUID> {

    /** Every entry for a classroom (GET /pools/{poolId}/class-reserve) — not scoped to a single
     *  pool, since Class Reserve is a classroom-level concept multiple pools can contribute to
     *  over time (contract's own description; this phase is its first appearance). */
    List<ClassReserveEntry> findByClassroomIdOrderByCreatedAtAsc(UUID classroomId);
}

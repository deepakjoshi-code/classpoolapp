package app.classpool.api.repository;

import app.classpool.api.domain.Pool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PoolRepository extends JpaRepository<Pool, UUID> {

    List<Pool> findByClassroomIdOrderByCreatedAtDesc(UUID classroomId);

    /** Batch form for ClassroomAssembler, which attaches a pools summary to many classrooms at once. */
    List<Pool> findByClassroomIdInOrderByCreatedAtDesc(List<UUID> classroomIds);
}

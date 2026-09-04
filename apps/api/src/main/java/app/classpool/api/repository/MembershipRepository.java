package app.classpool.api.repository;

import app.classpool.api.domain.Membership;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /**
     * The single query the whole cross-tenant authorization model rests on (PRD §14): does this
     * user have ANY Membership row on this exact classroom? Every classroom-scoped endpoint must
     * call this (or an equivalent classroom_id-filtered query) before returning anything.
     */
    boolean existsByClassroom_IdAndParentUserId(UUID classroomId, UUID parentUserId);

    @EntityGraph(attributePaths = {"classroom", "student"})
    List<Membership> findByClassroom_IdAndParentUserId(UUID classroomId, UUID parentUserId);

    Optional<Membership> findByClassroom_IdAndParentUserIdAndStudent_Id(UUID classroomId, UUID parentUserId,
                                                                          UUID studentId);

    @EntityGraph(attributePaths = {"classroom", "student"})
    List<Membership> findByParentUserIdOrderByCreatedAtAsc(UUID parentUserId);

    long countByClassroom_Id(UUID classroomId);
}

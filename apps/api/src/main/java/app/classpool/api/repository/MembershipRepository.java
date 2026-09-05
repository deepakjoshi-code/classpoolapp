package app.classpool.api.repository;

import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Every participating-student Membership on a classroom, in join order (PRD §6's
     * "first-joined-first-served" tie-break for allocating scarce pool supply —
     * {@code AllocationService.reconcile}). ORGANIZER-only rows (student null) are excluded by the
     * query itself, same instinct as {@link #countDistinctStudentsByClassroom_Id}.
     */
    @EntityGraph(attributePaths = {"student"})
    List<Membership> findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(UUID classroomId);

    long countByClassroom_Id(UUID classroomId);

    boolean existsByClassroom_IdAndParentUserIdAndRoleIn(UUID classroomId, UUID parentUserId,
                                                          Collection<MembershipRole> roles);

    /**
     * The single "is this caller an organizer/co-organizer on this classroom?" check — extracted
     * here so every organizer-only endpoint (invite creation, pool creation, requirement
     * add/edit/remove/confirm) shares one query instead of each service re-deriving its own
     * ORGANIZER-or-CO_ORGANIZER membership lookup.
     */
    default boolean hasOrganizerRole(UUID classroomId, UUID parentUserId) {
        return existsByClassroom_IdAndParentUserIdAndRoleIn(classroomId, parentUserId,
                List.of(MembershipRole.ORGANIZER, MembershipRole.CO_ORGANIZER));
    }

    /**
     * Distinct students already joined to this classroom (PRD §3.4's "confirmed participating
     * students") — counts Membership rows with a non-null student_id, since an ORGANIZER-only
     * grant (student_id null) never represents a participating student.
     */
    @Query("select count(distinct m.student.id) from Membership m where m.classroom.id = :classroomId "
            + "and m.student is not null")
    long countDistinctStudentsByClassroom_Id(@Param("classroomId") UUID classroomId);
}

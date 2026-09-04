package app.classpool.api.repository;

import app.classpool.api.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StudentRepository extends JpaRepository<Student, UUID> {
    List<Student> findByHouseholdId(UUID householdId);
}

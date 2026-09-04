package app.classpool.api.service;

import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.School;
import app.classpool.api.domain.SchoolYear;
import app.classpool.api.dto.ClassroomResponse;
import app.classpool.api.repository.SchoolRepository;
import app.classpool.api.repository.SchoolYearRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the API-facing ClassroomResponse (which flattens in school id/name and school-year
 * label) from the Classroom entity, which only stores a school_year_id FK. Batches the
 * SchoolYear/School lookups across a list of classrooms to avoid N+1 queries.
 */
@Component
public class ClassroomAssembler {

    private final SchoolYearRepository schoolYearRepository;
    private final SchoolRepository schoolRepository;

    public ClassroomAssembler(SchoolYearRepository schoolYearRepository, SchoolRepository schoolRepository) {
        this.schoolYearRepository = schoolYearRepository;
        this.schoolRepository = schoolRepository;
    }

    public ClassroomResponse toResponse(Classroom classroom) {
        return toResponses(List.of(classroom)).get(classroom.getId());
    }

    public Map<UUID, ClassroomResponse> toResponses(List<Classroom> classrooms) {
        if (classrooms.isEmpty()) {
            return Map.of();
        }
        List<UUID> schoolYearIds = classrooms.stream().map(Classroom::getSchoolYearId).distinct().toList();
        Map<UUID, SchoolYear> schoolYears = schoolYearRepository.findAllById(schoolYearIds).stream()
                .collect(Collectors.toMap(SchoolYear::getId, Function.identity()));

        List<UUID> schoolIds = schoolYears.values().stream().map(SchoolYear::getSchoolId).distinct().toList();
        Map<UUID, School> schools = schoolRepository.findAllById(schoolIds).stream()
                .collect(Collectors.toMap(School::getId, Function.identity()));

        return classrooms.stream().collect(Collectors.toMap(Classroom::getId, classroom -> {
            SchoolYear schoolYear = schoolYears.get(classroom.getSchoolYearId());
            School school = schoolYear == null ? null : schools.get(schoolYear.getSchoolId());
            return new ClassroomResponse(
                    classroom.getId(),
                    school == null ? null : school.getId(),
                    school == null ? null : school.getName(),
                    schoolYear == null ? null : schoolYear.getLabel(),
                    classroom.getGrade(),
                    classroom.getTeacherLabel(),
                    classroom.getStudentCountEstimate(),
                    classroom.getCreatedAt()
            );
        }));
    }
}

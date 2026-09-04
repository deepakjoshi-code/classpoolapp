package app.classpool.api.service;

import app.classpool.api.domain.School;
import app.classpool.api.dto.SchoolResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.SchoolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SchoolService {

    private final SchoolRepository schoolRepository;

    public SchoolService(SchoolRepository schoolRepository) {
        this.schoolRepository = schoolRepository;
    }

    @Transactional(readOnly = true)
    public List<SchoolResponse> search(String q) {
        return schoolRepository.fuzzySearch(q).stream().map(SchoolResponse::from).toList();
    }

    @Transactional
    public SchoolResponse create(String name) {
        School school = schoolRepository.save(new School(name.trim()));
        return SchoolResponse.from(school);
    }

    @Transactional(readOnly = true)
    public School getById(UUID id) {
        return schoolRepository.findById(id).orElseThrow(() -> new NotFoundException("School not found: " + id));
    }

    /**
     * Resolves the school for a CreateClassroomRequest: either the given schoolId, or a new
     * School created from schoolName. The client is expected to have called /schools/search
     * first (contract note on POST /schools) — this does not itself re-run dedup, that already
     * happened client-side against /schools/search before this request was made.
     */
    @Transactional
    public School resolveForClassroomCreation(UUID schoolId, String schoolName) {
        if (schoolId != null) {
            return getById(schoolId);
        }
        if (schoolName != null && !schoolName.isBlank()) {
            return schoolRepository.save(new School(schoolName.trim()));
        }
        throw new BadRequestException("Either schoolId or schoolName is required");
    }
}

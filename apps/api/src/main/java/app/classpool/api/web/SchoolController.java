package app.classpool.api.web;

import app.classpool.api.dto.CreateSchoolRequest;
import app.classpool.api.dto.SchoolResponse;
import app.classpool.api.service.SchoolService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/schools")
@Validated
public class SchoolController {

    private final SchoolService schoolService;

    public SchoolController(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    @GetMapping("/search")
    public List<SchoolResponse> search(@RequestParam @NotBlank @Size(min = 2) String q) {
        return schoolService.search(q);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolResponse create(@Valid @RequestBody CreateSchoolRequest request) {
        return schoolService.create(request.name());
    }
}

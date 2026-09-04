package app.classpool.api.dto;

import app.classpool.api.domain.School;

import java.util.UUID;

public record SchoolResponse(UUID id, String name) {
    public static SchoolResponse from(School school) {
        return new SchoolResponse(school.getId(), school.getName());
    }
}

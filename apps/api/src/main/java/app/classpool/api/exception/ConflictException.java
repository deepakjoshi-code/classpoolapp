package app.classpool.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown for a request that is well-formed and authorized but conflicts with the current state
 * machine (PRD §13.2/§13.3) — e.g. editing a Requirement once its Pool has left DRAFT, or
 * confirming a Pool twice. Always 409, matching contracts/openapi.yaml's Phase 3 responses.
 */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}

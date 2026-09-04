package app.classpool.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the caller is authenticated but has no Membership on the classroom being accessed.
 * This is the PRD §14 "Class A can never read Class B" boundary — it must resolve to a 403,
 * never a 404 (which would leak whether the id exists) and never a silent empty/partial response.
 */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}

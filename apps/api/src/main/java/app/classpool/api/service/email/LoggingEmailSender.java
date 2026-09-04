package app.classpool.api.service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs the email instead of sending it — there's no real SES access in this environment
 * (ARCHITECTURE.md §2 names SES as the intended provider). Swap for a real SES-backed
 * {@link EmailSender} when that's available; nothing else in the auth flow needs to change.
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("=== [LoggingEmailSender] Would send email ===\nTo: {}\nSubject: {}\nBody:\n{}\n===============================================",
                toEmail, subject, body);
    }
}

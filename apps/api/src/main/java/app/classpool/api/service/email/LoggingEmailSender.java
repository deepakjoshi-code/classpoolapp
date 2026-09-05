package app.classpool.api.service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Logs the email instead of sending it — there's no real SES access in this environment
 * (ARCHITECTURE.md §2 names SES as the intended provider). Default {@code EmailSender} bean
 * (active whenever {@code classpool.email.provider} isn't set to something else); see
 * {@link ResendEmailSender} for the one real-delivery alternative wired up so far.
 */
@Component
@ConditionalOnProperty(prefix = "classpool.email", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("=== [LoggingEmailSender] Would send email ===\nTo: {}\nSubject: {}\nBody:\n{}\n===============================================",
                toEmail, subject, body);
    }
}

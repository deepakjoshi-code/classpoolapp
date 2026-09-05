package app.classpool.api.service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Sends real email via Resend's HTTP API (https://resend.com/docs/api-reference/emails/send-email)
 * — a drop-in swap for {@link LoggingEmailSender}, activated by setting {@code EMAIL_PROVIDER=resend}
 * (see application.yml). Used for staging/manual testing where someone actually needs the magic
 * link in their inbox; {@link LoggingEmailSender} remains the default everywhere else (local dev,
 * tests, CI) since it needs no external account.
 *
 * <p>Resend's free tier only relays to the address that owns the API key's account unless a
 * sending domain is verified — so every recipient in a manual multi-parent test must be that same
 * address (or a "+" sub-address of it, e.g. {@code you+parent1@gmail.com}), until a domain is
 * verified in the Resend dashboard.
 */
@Component
@ConditionalOnProperty(prefix = "classpool.email", name = "provider", havingValue = "resend")
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final RestClient restClient;
    private final String apiKey;
    private final String fromAddress;

    public ResendEmailSender(
            @Value("${classpool.email.resend.api-key}") String apiKey,
            @Value("${classpool.email.from}") String fromAddress) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.restClient = RestClient.create();
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        try {
            restClient.post()
                    .uri(RESEND_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", fromAddress,
                            "to", new String[] {toEmail},
                            "subject", subject,
                            // Plain text body wrapped in <pre> — the magic-link body is already
                            // plain text with a bare URL, so this is enough to make it clickable.
                            "html", "<pre style=\"font-family: inherit; white-space: pre-wrap;\">"
                                    + body.replace("&", "&amp;").replace("<", "&lt;") + "</pre>"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Never let an email-delivery failure break the auth flow the email is for — the
            // magic-link token is already persisted by the time this is called (AuthService),
            // so a failed send just means the user doesn't get the email, not a broken request.
            log.error("Failed to send email via Resend to {}: {}", toEmail, e.getMessage());
        }
    }
}

package app.classpool.api.service.email;

/**
 * Outbound email boundary. {@link LoggingEmailSender} is the only implementation wired up in
 * this environment (no live SES access) — a real SES adapter is a drop-in: implement this
 * interface and swap the bean (e.g. behind a Spring profile), nothing else in the auth service
 * changes.
 */
public interface EmailSender {
    void send(String toEmail, String subject, String body);
}

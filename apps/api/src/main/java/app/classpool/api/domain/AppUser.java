package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue
    private UUID id;

    // Postgres citext (case-insensitive email) — reads/writes fine as a plain String/VARCHAR
    // binding (Postgres accepts text input for citext transparently); columnDefinition is
    // documentation only, since ddl-auto is `none` (Flyway owns the schema — see README).
    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String phone;

    @Column(name = "phone_sms_opt_in", nullable = false)
    private boolean phoneSmsOptIn = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    private AuthProvider authProvider;

    @Column(name = "auth_provider_sub")
    private String authProviderSub;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(String email, String displayName, AuthProvider authProvider, String authProviderSub) {
        this.email = email;
        this.displayName = displayName;
        this.authProvider = authProvider;
        this.authProviderSub = authProviderSub;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isPhoneSmsOptIn() {
        return phoneSmsOptIn;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public String getAuthProviderSub() {
        return authProviderSub;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

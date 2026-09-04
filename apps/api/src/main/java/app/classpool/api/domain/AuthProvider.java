package app.classpool.api.domain;

/** Matches the app_user.auth_provider check constraint in the V1 migration. */
public enum AuthProvider {
    GOOGLE,
    APPLE,
    MAGIC_LINK
}

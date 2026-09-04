package app.classpool.api.repository;

import app.classpool.api.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByAuthProviderAndAuthProviderSub(app.classpool.api.domain.AuthProvider provider, String sub);
}

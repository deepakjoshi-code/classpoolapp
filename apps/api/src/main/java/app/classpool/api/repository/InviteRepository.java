package app.classpool.api.repository;

import app.classpool.api.domain.Invite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InviteRepository extends JpaRepository<Invite, UUID> {
    Optional<Invite> findByToken(String token);
}

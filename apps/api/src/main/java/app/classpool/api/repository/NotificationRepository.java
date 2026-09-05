package app.classpool.api.repository;

import app.classpool.api.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** The caller's own inbox, newest first (GET /notifications/mine). */
    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);
}

package app.classpool.api.service;

import app.classpool.api.domain.Notification;
import app.classpool.api.domain.NotificationType;
import app.classpool.api.dto.NotificationResponse;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * PRD §11.3's in-app notification inbox. {@link #notify} is the single write path every emitting
 * service calls — {@code PaymentService.generatePayments} ({@link NotificationType#PAYMENT_DUE}),
 * {@code DistributionService.generateDistribution} ({@link NotificationType#BUNDLE_READY}), and
 * {@code PoolService.complete} ({@link NotificationType#POOL_COMPLETED}) — see apps/api/README.md's
 * "Notifications and savings summary (Phase 12)" notes for the full write-up, including why every
 * row here is {@code channel = PUSH} and why {@code sentAt} is set immediately rather than by a
 * real delivery queue.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * Persists one {@link Notification} row. Never throws for a bad recipient — every call site
     * already resolved {@code userId} from a real {@code Membership}/{@code Household} row before
     * calling this, so there is nothing to validate here; this method's only job is "write the
     * row," matching {@code Payment}/{@code AllocationLine}'s own "freeze, don't recompute"
     * simplicity.
     */
    @Transactional
    public void notify(UUID userId, NotificationType type, UUID poolId, String message) {
        notificationRepository.save(new Notification(userId, type, poolId, message));
    }

    /** The caller's own inbox (contract), newest first. Empty list — never an error — for a caller
     *  with no notifications yet. */
    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications(UUID callerUserId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(callerUserId).stream()
                .map(NotificationService::toResponse)
                .toList();
    }

    /**
     * Caller marks their own notification read (contract). 404 if the id doesn't exist at all,
     * 403 if it exists but belongs to someone else — same two-step "find, then check ownership"
     * shape as {@code PaymentService.payMyPayment}. Idempotent: a notification that's already read
     * is returned unchanged, no error (see {@link Notification#markRead()}).
     */
    @Transactional
    public NotificationResponse markRead(UUID callerUserId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));
        if (!notification.getUserId().equals(callerUserId)) {
            throw new ForbiddenException("Caller does not own this notification");
        }
        notification.markRead();
        notificationRepository.save(notification);
        return toResponse(notification);
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getPoolId(),
                notification.getMessage(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}

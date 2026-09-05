package app.classpool.api.service;

import app.classpool.api.domain.Notification;
import app.classpool.api.domain.NotificationType;
import app.classpool.api.dto.NotificationResponse;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit coverage of {@link NotificationService} — the one write path ({@link
 * NotificationService#notify}), the "mine" read-back, and the idempotent/ownership-gated read
 * action.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationService notificationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID poolId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository);
    }

    @Test
    void notify_persistsAPushNotification_withPoolIdAndMessage() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationService.notify(userId, NotificationType.PAYMENT_DUE, poolId, "You owe $4.52 for Fall Supplies.");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getType()).isEqualTo(NotificationType.PAYMENT_DUE);
        assertThat(saved.getPoolId()).isEqualTo(poolId);
        assertThat(saved.getMessage()).isEqualTo("You owe $4.52 for Fall Supplies.");
        assertThat(saved.getChannel().name()).isEqualTo("PUSH");
        assertThat(saved.getSentAt()).isNotNull();
        assertThat(saved.getReadAt()).isNull();
    }

    @Test
    void notify_allowsANullPoolId() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationService.notify(userId, NotificationType.CLASS_INVITE, null, "You're invited.");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getPoolId()).isNull();
    }

    @Test
    void getMyNotifications_returnsTheCallersOwnInbox_newestFirst() {
        Notification n1 = new Notification(userId, NotificationType.PAYMENT_DUE, poolId, "You owe $1.00.");
        setField(n1, "id", UUID.randomUUID());
        setField(n1, "createdAt", java.time.Instant.now());
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(n1));

        List<NotificationResponse> notifications = notificationService.getMyNotifications(userId);

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).type()).isEqualTo("PAYMENT_DUE");
        assertThat(notifications.get(0).poolId()).isEqualTo(poolId);
        assertThat(notifications.get(0).message()).isEqualTo("You owe $1.00.");
        assertThat(notifications.get(0).readAt()).isNull();
    }

    @Test
    void markRead_throwsNotFound_whenNotificationDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(notificationRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(userId, missingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void markRead_throwsForbidden_whenCallerDoesNotOwnIt() {
        Notification notification = new Notification(UUID.randomUUID(), NotificationType.PAYMENT_DUE, poolId, "msg");
        setField(notification, "id", UUID.randomUUID());
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markRead(userId, notification.getId()))
                .isInstanceOf(ForbiddenException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markRead_setsReadAt_andIsIdempotentOnASecondCall() {
        Notification notification = new Notification(userId, NotificationType.PAYMENT_DUE, poolId, "msg");
        setField(notification, "id", UUID.randomUUID());
        setField(notification, "createdAt", java.time.Instant.now());
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationResponse first = notificationService.markRead(userId, notification.getId());
        assertThat(first.readAt()).isNotNull();

        // Second call on an already-read notification: no error, unchanged readAt.
        NotificationResponse second = notificationService.markRead(userId, notification.getId());
        assertThat(second.readAt()).isEqualTo(first.readAt());
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}

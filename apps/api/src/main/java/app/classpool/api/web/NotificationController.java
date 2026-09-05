package app.classpool.api.web;

import app.classpool.api.dto.NotificationResponse;
import app.classpool.api.service.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 12's in-app notification inbox — a new controller since nothing else lives under
 * {@code /notifications} (same size/prefix-driven judgment call every controller split since
 * Phase 8 has made).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/mine")
    public List<NotificationResponse> getMyNotifications(@AuthenticationPrincipal UUID callerUserId) {
        return notificationService.getMyNotifications(callerUserId);
    }

    @PostMapping("/{notificationId}/read")
    public NotificationResponse markNotificationRead(@AuthenticationPrincipal UUID callerUserId,
                                                       @PathVariable UUID notificationId) {
        return notificationService.markRead(callerUserId, notificationId);
    }
}

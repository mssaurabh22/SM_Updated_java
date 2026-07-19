package com.salesmanager.crm.notification;

import com.salesmanager.crm.notification.dto.NotificationResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - NotificationService enforces the "always scoped to the current user"
 * rule, same layering as every other feature controller in this codebase.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public Page<NotificationResponse> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                            Pageable pageable) {
        return notificationService.listForCurrentUser(unreadOnly, pageable).map(NotificationResponse::from);
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable UUID id) {
        return NotificationResponse.from(notificationService.markRead(id));
    }
}

package com.salesmanager.crm.notification;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.security.CurrentUser;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal per-employee notification inbox. Every read/write here is always scoped to the
 * CURRENT user's own recipientId - never lets anyone (including an ADMIN) read or mark
 * another employee's notifications, matching the same information-hiding principle used
 * elsewhere (NotFoundException, not 403, for a notification that exists but isn't yours).
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final CurrentUser currentUser;

    public NotificationService(NotificationRepository notificationRepository, CurrentUser currentUser) {
        this.notificationRepository = notificationRepository;
        this.currentUser = currentUser;
    }

    // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
    @Transactional
    public Notification create(UUID recipientId, NotificationType type, String payload) {
        Notification notification = Notification.builder()
                .recipientId(recipientId)
                .type(type)
                .payload(payload)
                .read(false)
                .build();
        return notificationRepository.saveAndFlush(notification);
    }

    @Transactional(readOnly = true)
    public Page<Notification> listForCurrentUser(boolean unreadOnly, Pageable pageable) {
        UUID recipientId = currentUser.get().getEmployeeId();
        if (unreadOnly) {
            return notificationRepository.findByRecipientIdAndRead(recipientId, false, pageable);
        }
        return notificationRepository.findByRecipientId(recipientId, pageable);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public Notification markRead(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Notification not found: " + id));
        UUID recipientId = currentUser.get().getEmployeeId();
        if (!notification.getRecipientId().equals(recipientId)) {
            throw new NotFoundException("Notification not found: " + id);
        }
        notification.setRead(true);
        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        return notificationRepository.saveAndFlush(notification);
    }
}

package com.salesmanager.crm.employeeactivity;

import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and lists the employee-activity history (Leave lifecycle events - see
 * EmployeeActivityLog's class javadoc for why this is a separate table/service from
 * activity.ActivityLogService). {@link #record} is called directly (synchronously, same
 * transaction) from leave.LeaveRequestService's own @Transactional methods, same "pure history
 * of something that already successfully happened in the same transaction" reasoning as
 * ActivityLogService#record.
 */
@Service
public class EmployeeActivityLogService {

    private final EmployeeActivityLogRepository employeeActivityLogRepository;
    private final CurrentUser currentUser;

    public EmployeeActivityLogService(EmployeeActivityLogRepository employeeActivityLogRepository,
                                       CurrentUser currentUser) {
        this.employeeActivityLogRepository = employeeActivityLogRepository;
        this.currentUser = currentUser;
    }

    // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
    @Transactional
    public EmployeeActivityLog record(UUID employeeId, EmployeeActivityType type, UUID actorId, String description) {
        EmployeeActivityLog employeeActivityLog = EmployeeActivityLog.builder()
                .employeeId(employeeId)
                .type(type)
                .actorId(actorId)
                .description(description)
                .build();
        return employeeActivityLogRepository.saveAndFlush(employeeActivityLog);
    }

    /**
     * Same EMPLOYEE-forced-to-own-id / ADMIN-sees-any-employeeId-filter visibility rule as
     * ActivityLogService#list's EMPLOYEE-forced-to-own-leads rule: an EMPLOYEE's employeeId
     * filter is silently forced to their own id regardless of what was requested (so they can
     * never see a colleague's leave-activity history via query manipulation); ADMIN gets
     * whatever employeeId filter (or none) was requested, honored as-is, org-wide.
     */
    @Transactional(readOnly = true)
    public Page<EmployeeActivityLog> list(UUID requestedEmployeeId, EmployeeActivityType type, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        UUID employeeId = principal.getRole() == Role.EMPLOYEE ? principal.getEmployeeId() : requestedEmployeeId;

        if (employeeId != null && type != null) {
            return employeeActivityLogRepository.findByEmployeeIdAndType(employeeId, type, pageable);
        }
        if (employeeId != null) {
            return employeeActivityLogRepository.findByEmployeeId(employeeId, pageable);
        }
        if (type != null) {
            return employeeActivityLogRepository.findByType(type, pageable);
        }
        return employeeActivityLogRepository.findAll(pageable);
    }
}

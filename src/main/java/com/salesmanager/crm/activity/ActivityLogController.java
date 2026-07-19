package com.salesmanager.crm.activity;

import com.salesmanager.crm.activity.dto.ActivityResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - ActivityLogService enforces the EMPLOYEE-forced-to-own-leads visibility
 * rule, same layering as every other feature controller in this codebase. Serves both a
 * single-Lead timeline ({@code ?leadId=X}) and a broader org-wide/personal activity feed (no
 * leadId, optionally narrowed by ownerId/type) - one flexible endpoint, not two. Every
 * endpoint here is open to both ADMIN and EMPLOYEE; the service enforces visibility rather
 * than a controller-level @PreAuthorize.
 *
 * Results are newest-first ({@code createdAt} DESC) by default - a journey/activity feed
 * reads naturally with the most recent event on top, same convention as an inbox.
 */
@RestController
@RequestMapping("/activity")
public class ActivityLogController {

    private final ActivityLogService activityLogService;

    public ActivityLogController(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @GetMapping
    public Page<ActivityResponse> list(@RequestParam(required = false) UUID leadId,
                                        @RequestParam(required = false) UUID ownerId,
                                        @RequestParam(required = false) ActivityType type,
                                        @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        ActivityFilter filter = new ActivityFilter(leadId, ownerId, type);
        return activityLogService.list(filter, pageable).map(ActivityResponse::from);
    }
}

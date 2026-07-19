package com.salesmanager.crm.leave;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.leave.dto.HolidayCreateRequest;
import com.salesmanager.crm.leave.dto.HolidayResponse;
import jakarta.validation.Valid;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-configurable company holiday calendar. GET open to any authenticated user (an employee
 * submitting a leave request benefits from seeing the calendar too); mutations ADMIN-only.
 * Every endpoint requires EMPLOYEE_LEAVE_MANAGEMENT, per Part B of the entitlement plan.
 */
@RestController
@RequestMapping("/holidays")
public class HolidayController {

    private final HolidayService holidayService;

    public HolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<HolidayResponse> list(@RequestParam(required = false) Integer year) {
        int resolvedYear = year != null ? year : Year.now().getValue();
        return holidayService.list(resolvedYear).stream()
                .map(HolidayResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayResponse create(@Valid @RequestBody HolidayCreateRequest request) {
        return HolidayResponse.from(holidayService.create(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        holidayService.delete(id);
    }
}

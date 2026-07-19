package com.salesmanager.crm.visit;

import com.salesmanager.crm.visit.dto.VisitCreateRequest;
import com.salesmanager.crm.visit.dto.VisitResponse;
import com.salesmanager.crm.visit.dto.VisitStatusUpdateRequest;
import com.salesmanager.crm.visit.dto.VisitUpdateRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - all business logic (validation, ownership-via-parent-lead enforcement,
 * pre-fill sync-back, follow-up event publishing) lives in VisitService, same layering as
 * LeadController. Every endpoint here is open to both ADMIN and EMPLOYEE - VisitService
 * itself enforces the owner-scoped visibility rule rather than a controller-level
 * @PreAuthorize.
 */
@RestController
@RequestMapping("/visits")
public class VisitController {

    private final VisitService visitService;

    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }

    @GetMapping
    public Page<VisitResponse> list(@RequestParam(required = false) UUID leadId,
                                     @RequestParam(required = false) VisitStatus status,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                     Pageable pageable) {
        VisitFilter filter = new VisitFilter(leadId, status, dateFrom, dateTo);
        return visitService.list(filter, pageable).map(VisitResponse::from);
    }

    // Must be declared (or otherwise resolved) ahead of GET /{id} in Spring MVC's route
    // matching for the literal "/today" segment to win over the {id} path variable - see
    // LeadController's identical comment re: "/duplicates".
    @GetMapping("/today")
    public List<VisitResponse> today() {
        return visitService.getTodaysFollowUps().stream().map(VisitResponse::from).toList();
    }

    @GetMapping("/{id}")
    public VisitResponse getById(@PathVariable UUID id) {
        return VisitResponse.from(visitService.getById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VisitResponse create(@Valid @RequestBody VisitCreateRequest request) {
        return VisitResponse.from(visitService.create(request));
    }

    @PutMapping("/{id}")
    public VisitResponse update(@PathVariable UUID id, @Valid @RequestBody VisitUpdateRequest request) {
        return VisitResponse.from(visitService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public VisitResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody VisitStatusUpdateRequest request) {
        return VisitResponse.from(visitService.updateStatus(id, request));
    }
}

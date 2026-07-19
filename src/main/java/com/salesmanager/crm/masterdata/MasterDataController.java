package com.salesmanager.crm.masterdata;

import com.salesmanager.crm.masterdata.dto.MasterDataCreateRequest;
import com.salesmanager.crm.masterdata.dto.MasterDataResponse;
import com.salesmanager.crm.masterdata.dto.MasterDataUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic CRUD over the shared master_data table, scoped by {@code type} in the path.
 * GET is open to any authenticated user (both ADMIN and EMPLOYEE need dropdown data);
 * mutating endpoints are ADMIN-only.
 */
@RestController
@RequestMapping("/masters/{type}")
public class MasterDataController {

    private final MasterDataService masterDataService;

    public MasterDataController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @GetMapping
    public List<MasterDataResponse> list(@PathVariable MasterType type,
                                          @RequestParam(defaultValue = "false") boolean includeInactive) {
        return masterDataService.list(type, includeInactive).stream()
                .map(MasterDataResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MasterDataResponse create(@PathVariable MasterType type,
                                      @Valid @RequestBody MasterDataCreateRequest request) {
        return MasterDataResponse.from(masterDataService.create(type, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MasterDataResponse update(@PathVariable MasterType type, @PathVariable UUID id,
                                      @Valid @RequestBody MasterDataUpdateRequest request) {
        return MasterDataResponse.from(masterDataService.update(type, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable MasterType type, @PathVariable UUID id) {
        masterDataService.deactivate(type, id);
    }
}

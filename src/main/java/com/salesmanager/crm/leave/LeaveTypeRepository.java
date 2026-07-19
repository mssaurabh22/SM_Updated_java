package com.salesmanager.crm.leave;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as MasterDataRepository - no manual
 * "WHERE organizationId = ..." here.
 */
public interface LeaveTypeRepository extends JpaRepository<LeaveType, UUID> {

    List<LeaveType> findByActive(boolean active, Sort sort);

    boolean existsByCodeIgnoreCase(String code);
}

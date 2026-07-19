package com.salesmanager.crm.masterdata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as EmployeeRepository - no manual
 * "WHERE organizationId = ..." here.
 */
public interface MasterDataRepository extends JpaRepository<MasterData, UUID> {

    List<MasterData> findByType(MasterType type, Sort sort);

    List<MasterData> findByTypeAndActive(MasterType type, boolean active, Sort sort);

    boolean existsByTypeAndCodeIgnoreCase(MasterType type, String code);

    /**
     * Used by LeadService's Lost-lead workflow to look up this org's INTEREST_LEVEL/COLD row
     * (every org gets one seeded by MasterDataSeedService) so it can auto-set a lead's
     * interestLevelId when it is marked LOST.
     */
    Optional<MasterData> findByTypeAndCodeIgnoreCase(MasterType type, String code);
}

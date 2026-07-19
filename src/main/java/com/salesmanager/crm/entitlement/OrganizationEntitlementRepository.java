package com.salesmanager.crm.entitlement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationEntitlementRepository extends JpaRepository<OrganizationEntitlement, UUID> {

    Optional<OrganizationEntitlement> findByOrganizationIdAndEntitlementCode(UUID organizationId, FeatureEntitlement code);

    List<OrganizationEntitlement> findByOrganizationId(UUID organizationId);
}

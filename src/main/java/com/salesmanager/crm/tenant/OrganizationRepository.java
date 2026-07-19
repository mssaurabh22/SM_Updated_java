package com.salesmanager.crm.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySubdomain(String subdomain);

    boolean existsBySubdomain(String subdomain);
}

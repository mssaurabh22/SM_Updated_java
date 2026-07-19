package com.salesmanager.crm.leave;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping - no manual "WHERE organizationId = ..." here.
 */
public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    /** Backs HolidayService#list(year) - start/end are that year's Jan 1 / Dec 31. */
    List<Holiday> findByHolidayDateBetween(LocalDate start, LocalDate end, Sort sort);

    /** Backs LeaveRequestService's totalDays computation - which dates in a requested range are holidays. */
    List<Holiday> findByHolidayDateBetween(LocalDate start, LocalDate end);

    boolean existsByHolidayDate(LocalDate holidayDate);
}

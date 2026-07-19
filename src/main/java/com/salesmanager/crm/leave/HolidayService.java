package com.salesmanager.crm.leave;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.leave.dto.HolidayCreateRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-only CRUD for the company holiday calendar. No "active" concept, unlike LeaveType/
 * MasterData's soft-delete - a holiday either exists for a given date or it doesn't, so delete
 * is a plain hard delete (see Holiday's class javadoc).
 */
@Service
public class HolidayService {

    private final HolidayRepository holidayRepository;

    public HolidayService(HolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    @Transactional(readOnly = true)
    public List<Holiday> list(int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        return holidayRepository.findByHolidayDateBetween(start, end, Sort.by(Sort.Direction.ASC, "holidayDate"));
    }

    // noRollbackFor is essential, not cosmetic - see MasterDataService#create's identical
    // comment: TenantFilter wraps the whole request in one shared transaction, so an unmarked
    // RuntimeException here would poison it even though GlobalExceptionHandler translates this
    // into a normal 409 response.
    @Transactional(noRollbackFor = DuplicateHolidayException.class)
    public Holiday create(HolidayCreateRequest request) {
        if (holidayRepository.existsByHolidayDate(request.holidayDate())) {
            throw new DuplicateHolidayException(
                    "A holiday already exists for date " + request.holidayDate());
        }
        Holiday holiday = Holiday.builder()
                .name(request.name())
                .holidayDate(request.holidayDate())
                .build();
        // saveAndFlush (not save) - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        return holidayRepository.saveAndFlush(holiday);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public void delete(UUID id) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Holiday not found: " + id));
        holidayRepository.delete(holiday);
    }
}

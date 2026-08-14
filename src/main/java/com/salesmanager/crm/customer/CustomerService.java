package com.salesmanager.crm.customer;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.customer.dto.CustomerCreateRequest;
import com.salesmanager.crm.customer.dto.CustomerFilter;
import com.salesmanager.crm.customer.dto.CustomerUpdateRequest;
import com.salesmanager.crm.masterdata.MasterDataService;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.security.CurrentUser;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Org-wide Customer master - no owner-scoping (see Customer's class javadoc). Create/update are
 * open to any entitled employee (not ADMIN-only like Product), since the whole point is a rep
 * being able to quick-add a customer inline while drafting a Quotation in the field.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final MasterDataService masterDataService;
    private final CurrentUser currentUser;

    public CustomerService(CustomerRepository customerRepository, MasterDataService masterDataService,
                            CurrentUser currentUser) {
        this.customerRepository = customerRepository;
        this.masterDataService = masterDataService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<Customer> list(CustomerFilter filter, Pageable pageable) {
        Specification<Customer> spec = Specification
                .where(filter.includeInactive() ? null : CustomerSpecifications.isActive(true))
                .and(CustomerSpecifications.hasCity(filter.cityId()))
                .and(CustomerSpecifications.hasState(filter.stateId()))
                .and(CustomerSpecifications.matchesSearch(filter.search()));
        return customerRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Customer getById(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + id));
    }

    @Transactional
    public Customer create(CustomerCreateRequest request) {
        validateReferences(request.cityId(), request.stateId(), request.industryId());
        Customer customer = Customer.builder()
                .name(request.name())
                .contactPerson(request.contactPerson())
                .phone(request.phone())
                .email(request.email())
                .address(request.address())
                .cityId(request.cityId())
                .stateId(request.stateId())
                .industryId(request.industryId())
                .gstin(request.gstin())
                .notes(request.notes())
                .active(true)
                .createdBy(currentUser.get().getEmployeeId())
                .build();
        return customerRepository.saveAndFlush(customer);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public Customer update(UUID id, CustomerUpdateRequest request) {
        validateReferences(request.cityId(), request.stateId(), request.industryId());
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + id));
        customer.setName(request.name());
        customer.setContactPerson(request.contactPerson());
        customer.setPhone(request.phone());
        customer.setEmail(request.email());
        customer.setAddress(request.address());
        customer.setCityId(request.cityId());
        customer.setStateId(request.stateId());
        customer.setIndustryId(request.industryId());
        customer.setGstin(request.gstin());
        customer.setNotes(request.notes());
        customer.setActive(request.active());
        return customerRepository.saveAndFlush(customer);
    }

    private void validateReferences(UUID cityId, UUID stateId, UUID industryId) {
        masterDataService.validateReference(cityId, MasterType.CITY, "cityId");
        masterDataService.validateReference(stateId, MasterType.STATE, "stateId");
        masterDataService.validateReference(industryId, MasterType.INDUSTRY, "industryId");
    }
}

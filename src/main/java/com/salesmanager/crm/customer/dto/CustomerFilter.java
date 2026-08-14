package com.salesmanager.crm.customer.dto;

import java.util.UUID;

public record CustomerFilter(String search, UUID cityId, UUID stateId, boolean includeInactive) {
}

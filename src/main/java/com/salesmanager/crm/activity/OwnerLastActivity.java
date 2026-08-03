package com.salesmanager.crm.activity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Spring Data interface projection backing ActivityLogRepository#findLastActivityForOwners -
 * one row per distinct ownerId with at least one activity entry, for the Team Progress view's
 * "last activity" column. An owner with no activity at all simply doesn't appear in the result.
 */
public interface OwnerLastActivity {

    UUID getOwnerId();

    OffsetDateTime getLastActivityAt();
}

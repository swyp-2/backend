package com.baton.integration;

/**
 * @param role "MANAGER" 또는 "CREW"
 */
public record ActiveMembership(long membershipId, long storeId, long userId, String role) {
}

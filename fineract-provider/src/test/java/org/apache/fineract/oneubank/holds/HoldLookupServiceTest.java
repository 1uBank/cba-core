/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.oneubank.holds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class HoldLookupServiceTest {

    private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AppUser user = mock(AppUser.class);
    private final HoldLookupService service = new HoldLookupService(context, jdbc);

    @BeforeEach
    void signedIn() {
        when(context.authenticatedUser()).thenReturn(user);
    }

    private static Map<String, Object> row(Object releaseId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 95501L);
        row.put("savings_account_id", 158L);
        row.put("account_no", "0020001580");
        row.put("client_id", 10L);
        row.put("amount", new BigDecimal("2500.000000"));
        row.put("reason_for_block", "Electricity token purchase");
        row.put("external_id", "LIEN-1");
        row.put("created_on_utc", OffsetDateTime.of(2026, 11, 23, 9, 15, 0, 0, ZoneOffset.ofHours(1)));
        row.put("is_reversed", false);
        row.put("release_id", releaseId);
        row.put("released_on_utc", releaseId == null ? null : OffsetDateTime.of(2026, 11, 23, 10, 0, 0, 0, ZoneOffset.UTC));
        return row;
    }

    @Test
    void findsAnActiveHoldByItsReference() {
        when(jdbc.queryForList(anyString(), eq(20), eq("LIEN-1"))).thenReturn(List.of(row(null)));

        Map<String, Object> hold = service.byReference("LIEN-1").orElseThrow();

        assertThat(hold).containsEntry("holdTransactionId", 95501L).containsEntry("accountNo", "0020001580")
                .containsEntry("amount", "2500.000000").containsEntry("released", false)
                .containsEntry("createdOn", "2026-11-23T08:15:00Z").containsEntry("releasedOn", null);
    }

    @Test
    void findsAReleasedHoldByAccountAndTransaction() {
        when(jdbc.queryForList(anyString(), eq(20), eq(158L), eq(95501L))).thenReturn(List.of(row(95502L)));

        Map<String, Object> hold = service.byTransaction(158L, 95501L).orElseThrow();

        assertThat(hold).containsEntry("released", true).containsEntry("releaseTransactionId", 95502L)
                .containsEntry("releasedOn", "2026-11-23T10:00:00Z");
    }

    @Test
    void findsNothingForAnUnknownReference() {
        when(jdbc.queryForList(anyString(), eq(20), eq("NOPE"))).thenReturn(List.of());

        assertThat(service.byReference("NOPE")).isEmpty();
    }

    @Test
    void needsReadPermissionOnSavingsAccounts() {
        doThrow(new NoAuthorizationException("no")).when(user).validateHasReadPermission("SAVINGSACCOUNT");

        assertThatThrownBy(() -> service.byReference("LIEN-1")).isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(jdbc);
    }
}

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
package org.apache.fineract.oneubank.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
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

class TransferLookupServiceTest {

    private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AppUser user = mock(AppUser.class);
    private final TransferLookupService service = new TransferLookupService(context, jdbc);

    @BeforeEach
    void signedIn() {
        when(context.authenticatedUser()).thenReturn(user);
    }

    @Test
    void findsATransferByItsReference() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 812L);
        row.put("idempotency_key", "TRF-1");
        row.put("amount", new BigDecimal("1500.000000"));
        row.put("description", "Rent");
        row.put("transaction_date", Date.valueOf("2026-11-24"));
        row.put("is_reversed", false);
        row.put("from_savings_account_id", 158L);
        row.put("from_account_no", "0020001580");
        row.put("from_client_id", 10L);
        row.put("to_savings_account_id", 159L);
        row.put("to_account_no", "0020001597");
        row.put("to_client_id", 11L);
        row.put("to_client_name", "Bayo Ade");
        row.put("from_savings_transaction_id", 901L);
        row.put("to_savings_transaction_id", 902L);
        row.put("created_on_utc", OffsetDateTime.of(2026, 11, 24, 9, 0, 0, 0, ZoneOffset.UTC));
        when(jdbc.queryForList(anyString(), eq("TRF-1"))).thenReturn(List.of(row));

        assertThat(service.byReference("TRF-1").orElseThrow()).containsEntry("transferId", 812L).containsEntry("amount", "1500.000000")
                .containsEntry("toClientName", "Bayo Ade").containsEntry("transferDate", "2026-11-24")
                .containsEntry("createdOn", "2026-11-24T09:00:00Z").containsEntry("reversed", false);
        assertThat(service.byReference("TRF-1").orElseThrow()).containsEntry("fromAccountNo", "0020001580");
    }

    @Test
    void findsNothingForAnUnknownReference() {
        when(jdbc.queryForList(anyString(), eq("NOPE"))).thenReturn(List.of());

        assertThat(service.byReference("NOPE")).isEmpty();
    }

    @Test
    void needsReadPermissionOnTransfers() {
        doThrow(new NoAuthorizationException("no")).when(user).validateHasReadPermission("ACCOUNTTRANSFER");

        assertThatThrownBy(() -> service.byReference("TRF-1")).isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(jdbc);
    }
}

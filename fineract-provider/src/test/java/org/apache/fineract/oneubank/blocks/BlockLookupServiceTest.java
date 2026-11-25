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
package org.apache.fineract.oneubank.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings("unchecked")
class BlockLookupServiceTest {

    private final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BlockLookupService service = new BlockLookupService(context, jdbc);

    @BeforeEach
    void signedIn() {
        when(context.authenticatedUser()).thenReturn(mock(AppUser.class));
    }

    private void subStatus(int value) {
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq(158L))).thenReturn(List.of(value));
    }

    private static Map<String, Object> command(String action, String user, String reason, int minute) {
        return Map.of("action_name", action, "username", user, "command_as_json", "{\"reasonForBlock\":\"" + reason + "\"}",
                "made_on_date_utc", OffsetDateTime.of(2026, 11, 24, 9, minute, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void eachSideIsAttributedToTheCommandThatBlockedIt() {
        subStatus(400); // BLOCK: both sides
        when(jdbc.queryForList(anyString(), eq(158L), eq(1))).thenReturn(List.of(command("BLOCKCREDIT", "jane.staff", "Court order", 2),
                command("BLOCKDEBIT", "service-account-banking-api", "Fraud check", 1)));

        Map<String, Object> blocks = service.blocks(158L);

        assertThat((Map<String, Object>) blocks.get("debit")).containsEntry("blocked", true)
                .containsEntry("placedBy", "service-account-banking-api").containsEntry("reason", "Fraud check");
        assertThat((Map<String, Object>) blocks.get("credit")).containsEntry("blocked", true).containsEntry("placedBy", "jane.staff")
                .containsEntry("reason", "Court order").containsEntry("placedOn", "2026-11-24T09:02:00Z");
    }

    @Test
    void aBlockAllCountsForBothSides() {
        subStatus(600); // BLOCK_DEBIT, left after an all-block's credit side was lifted
        when(jdbc.queryForList(anyString(), eq(158L), eq(1))).thenReturn(List.of(command("BLOCK", "jane.staff", "PND", 0)));

        Map<String, Object> blocks = service.blocks(158L);

        assertThat((Map<String, Object>) blocks.get("debit")).containsEntry("placedBy", "jane.staff");
        assertThat((Map<String, Object>) blocks.get("credit")).containsEntry("blocked", false).doesNotContainKey("placedBy");
    }

    @Test
    void aSideBlockedWithNoCommandHasNoPlacer() {
        subStatus(500); // BLOCK_CREDIT, for example from a migration
        when(jdbc.queryForList(anyString(), eq(158L), eq(1))).thenReturn(List.of());

        assertThat((Map<String, Object>) service.blocks(158L).get("credit")).containsEntry("blocked", true).doesNotContainKey("placedBy");
    }
}

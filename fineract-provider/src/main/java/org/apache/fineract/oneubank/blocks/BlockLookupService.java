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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountSubStatusEnum;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What is blocked on a savings account, and for each side (debits, credits) who placed the block, why and when.
 * Fineract keeps one block state per account; the command pipeline keeps every block command with its maker and
 * request, so the latest command that blocked a side tells who placed what is in force. A side blocked with no such
 * command (for example by a data migration) has no placer.
 */
@Service
@RequiredArgsConstructor
public class BlockLookupService {

    private static final Set<String> DEBIT_BLOCKS = Set.of("BLOCK", "BLOCKDEBIT");
    private static final Set<String> CREDIT_BLOCKS = Set.of("BLOCK", "BLOCKCREDIT");
    private static final int PROCESSED = 1;

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> blocks(Long savingsId) {
        context.authenticatedUser().validateHasReadPermission("SAVINGSACCOUNT");
        List<Integer> subStatus = jdbcTemplate.queryForList("SELECT sub_status_enum FROM m_savings_account WHERE id = ?", Integer.class,
                savingsId);
        if (subStatus.isEmpty()) {
            throw new BlockAccountNotFoundException(savingsId);
        }
        SavingsAccountSubStatusEnum status = SavingsAccountSubStatusEnum.fromInt(subStatus.get(0) == null ? 0 : subStatus.get(0));
        boolean debit = status == SavingsAccountSubStatusEnum.BLOCK || status == SavingsAccountSubStatusEnum.BLOCK_DEBIT;
        boolean credit = status == SavingsAccountSubStatusEnum.BLOCK || status == SavingsAccountSubStatusEnum.BLOCK_CREDIT;
        List<Map<String, Object>> commands = jdbcTemplate.queryForList("""
                SELECT cs.action_name, u.username, cs.command_as_json, cs.made_on_date_utc
                FROM m_portfolio_command_source cs
                LEFT JOIN m_appuser u ON u.id = cs.maker_id
                WHERE cs.entity_name = 'SAVINGSACCOUNT' AND cs.savings_account_id = ? AND cs.status = ?
                  AND cs.action_name IN ('BLOCK', 'BLOCKDEBIT', 'BLOCKCREDIT')
                ORDER BY cs.id DESC""", savingsId, PROCESSED);

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("savingsId", savingsId);
        blocks.put("debit", side(debit, commands, DEBIT_BLOCKS));
        blocks.put("credit", side(credit, commands, CREDIT_BLOCKS));
        return blocks;
    }

    private static Map<String, Object> side(boolean blocked, List<Map<String, Object>> commands, Set<String> blockingActions) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("blocked", blocked);
        if (blocked) {
            commands.stream().filter(c -> blockingActions.contains(String.valueOf(c.get("action_name")))).findFirst().ifPresent(c -> {
                side.put("placedBy", c.get("username"));
                side.put("reason", reason(c.get("command_as_json")));
                side.put("placedOn", timestamp(c.get("made_on_date_utc")));
            });
        }
        return side;
    }

    private static String reason(Object json) {
        try {
            JsonElement reason = JsonParser.parseString(String.valueOf(json)).getAsJsonObject().get("reasonForBlock");
            return reason == null || reason.isJsonNull() ? null : reason.getAsString();
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static String timestamp(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        return value == null ? null : value.toString();
    }
}

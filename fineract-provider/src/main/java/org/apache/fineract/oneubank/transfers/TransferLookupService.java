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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Finds an account transfer by the reference it was made with. Fineract's account transfers have no external ID, but
 * the command pipeline keeps each request's {@code Idempotency-Key} with the ID of what it created, so the reference
 * the Banking API sends as that key leads to the transfer.
 */
@Service
@RequiredArgsConstructor
public class TransferLookupService {

    private static final String SELECT = """
            SELECT att.id, att.amount, att.description, att.transaction_date, att.is_reversed,
                   d.from_savings_account_id, fa.account_no AS from_account_no, d.from_client_id,
                   d.to_savings_account_id, ta.account_no AS to_account_no, d.to_client_id, tc.display_name AS to_client_name,
                   att.from_savings_transaction_id, att.to_savings_transaction_id, ft.created_on_utc, cs.idempotency_key
            FROM m_portfolio_command_source cs
            JOIN m_account_transfer_details d ON d.id = cs.resource_id
            JOIN m_account_transfer_transaction att ON att.account_transfer_details_id = d.id
            JOIN m_savings_account fa ON fa.id = d.from_savings_account_id
            JOIN m_savings_account ta ON ta.id = d.to_savings_account_id
            JOIN m_client tc ON tc.id = d.to_client_id
            LEFT JOIN m_savings_account_transaction ft ON ft.id = att.from_savings_transaction_id
            WHERE cs.entity_name = 'ACCOUNTTRANSFER' AND cs.action_name = 'CREATE' AND cs.idempotency_key = ?
            ORDER BY att.id
            """;

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;

    /** The savings-to-savings transfer made with this reference, if any. */
    public Optional<Map<String, Object>> byReference(String transactionReference) {
        context.authenticatedUser().validateHasReadPermission("ACCOUNTTRANSFER");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT, transactionReference);
        return rows.isEmpty() ? Optional.empty() : Optional.of(transfer(rows.get(0)));
    }

    private static Map<String, Object> transfer(Map<String, Object> row) {
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("transferId", row.get("id"));
        transfer.put("transactionReference", row.get("idempotency_key"));
        transfer.put("fromSavingsId", row.get("from_savings_account_id"));
        transfer.put("fromAccountNo", row.get("from_account_no"));
        transfer.put("fromClientId", row.get("from_client_id"));
        transfer.put("toSavingsId", row.get("to_savings_account_id"));
        transfer.put("toAccountNo", row.get("to_account_no"));
        transfer.put("toClientId", row.get("to_client_id"));
        transfer.put("toClientName", row.get("to_client_name"));
        transfer.put("amount", ((BigDecimal) row.get("amount")).toPlainString());
        transfer.put("description", row.get("description"));
        transfer.put("transferDate", String.valueOf(row.get("transaction_date")));
        transfer.put("fromTransactionId", row.get("from_savings_transaction_id"));
        transfer.put("toTransactionId", row.get("to_savings_transaction_id"));
        transfer.put("createdOn", timestamp(row.get("created_on_utc")));
        transfer.put("reversed", Boolean.TRUE.equals(row.get("is_reversed")));
        return transfer;
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

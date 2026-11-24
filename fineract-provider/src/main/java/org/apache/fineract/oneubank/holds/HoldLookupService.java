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

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Finds a hold (Fineract's "hold amount" savings transaction) by the reference it was placed with, which is stored as
 * the transaction's external ID, or by its account and transaction ID. Fineract can only look a transaction up within
 * an account; a hold reference alone is all an API client has.
 */
@Service
@RequiredArgsConstructor
public class HoldLookupService {

    private static final String SELECT = """
            SELECT t.id, t.savings_account_id, sa.account_no, sa.client_id, t.amount, t.reason_for_block, t.external_id,
                   t.created_on_utc, t.is_reversed, r.id AS release_id, r.created_on_utc AS released_on_utc
            FROM m_savings_account_transaction t
            JOIN m_savings_account sa ON sa.id = t.savings_account_id
            LEFT JOIN m_savings_account_transaction r ON r.id = t.release_id_of_hold_amount
            WHERE t.transaction_type_enum = ?
            """;

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;

    /** The hold placed with this reference, if any. */
    public Optional<Map<String, Object>> byReference(String transactionReference) {
        context.authenticatedUser().validateHasReadPermission("SAVINGSACCOUNT");
        return first(jdbcTemplate.queryForList(SELECT + " AND t.external_id = ?", SavingsAccountTransactionType.AMOUNT_HOLD.getValue(),
                transactionReference));
    }

    /** The hold with this transaction ID on this account, if any. */
    public Optional<Map<String, Object>> byTransaction(Long savingsId, Long transactionId) {
        context.authenticatedUser().validateHasReadPermission("SAVINGSACCOUNT");
        return first(jdbcTemplate.queryForList(SELECT + " AND t.savings_account_id = ? AND t.id = ?",
                SavingsAccountTransactionType.AMOUNT_HOLD.getValue(), savingsId, transactionId));
    }

    static PlatformApiDataValidationException invalidQuery() {
        return new PlatformApiDataValidationException(List.of(ApiParameterError.generalError("validation.msg.oneubank.hold.query.invalid",
                "Send transactionReference, or savingsId with transactionId")));
    }

    private static Optional<Map<String, Object>> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(hold(rows.get(0)));
    }

    private static Map<String, Object> hold(Map<String, Object> row) {
        Map<String, Object> hold = new LinkedHashMap<>();
        hold.put("holdTransactionId", row.get("id"));
        hold.put("savingsId", row.get("savings_account_id"));
        hold.put("accountNo", row.get("account_no"));
        hold.put("clientId", row.get("client_id"));
        hold.put("amount", ((BigDecimal) row.get("amount")).toPlainString());
        hold.put("reason", row.get("reason_for_block"));
        hold.put("transactionReference", row.get("external_id"));
        hold.put("createdOn", timestamp(row.get("created_on_utc")));
        hold.put("released", row.get("release_id") != null);
        hold.put("releaseTransactionId", row.get("release_id"));
        hold.put("releasedOn", timestamp(row.get("released_on_utc")));
        hold.put("reversed", Boolean.TRUE.equals(row.get("is_reversed")));
        return hold;
    }

    /** UTC timestamps as ISO 8601 text. */
    private static String timestamp(Object value) {
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        return value == null ? null : value.toString();
    }
}

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
package org.apache.fineract.oneubank.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Savings transactions that move money (holds, releases and accruals are left out), each with what Fineract's own
 * transaction read cannot give in one call: the reference it was made with, both sides of an account transfer, the
 * narration, the payment type, the virtual account type and the balance after it.
 */
@Service
@RequiredArgsConstructor
public class TransactionLookupService {

    static final int MAX_LIMIT = 100;
    /** Credits: deposit, interest posting, dividend payout. */
    static final List<Integer> CREDITS = List.of(1, 3, 8);
    /** Debits: withdrawal, withdrawal fee, annual fee, pay charge, overdraft interest, withhold tax, escheat. */
    static final List<Integer> DEBITS = List.of(2, 4, 5, 7, 17, 18, 19);

    private static final String SELECT = """
            SELECT t.id, t.savings_account_id, sa.account_no, sa.client_id, t.transaction_type_enum, t.amount,
                   t.transaction_date, t.created_on_utc, t.is_reversed, t.is_reversal, t.running_balance_derived,
                   t.external_id, pt.value AS payment_type, va.type AS va_type,
                   (SELECT n.note FROM m_note n WHERE n.savings_account_transaction_id = t.id ORDER BY n.id LIMIT 1) AS note,
                   att.id AS transfer_id, att.from_savings_transaction_id, att.to_savings_transaction_id,
                   att.description AS transfer_description, fa.account_no AS from_account_no, ta.account_no AS to_account_no,
                   fc.display_name AS from_name, tc.display_name AS to_name, cs.idempotency_key AS transfer_reference
            FROM m_savings_account_transaction t
            JOIN m_savings_account sa ON sa.id = t.savings_account_id
            LEFT JOIN m_payment_detail pd ON pd.id = t.payment_detail_id
            LEFT JOIN m_payment_type pt ON pt.id = pd.payment_type_id
            LEFT JOIN va_details va ON va.savings_account_id = t.savings_account_id
            LEFT JOIN m_account_transfer_transaction att
                   ON att.from_savings_transaction_id = t.id OR att.to_savings_transaction_id = t.id
            LEFT JOIN m_account_transfer_details d ON d.id = att.account_transfer_details_id
            LEFT JOIN m_savings_account fa ON fa.id = d.from_savings_account_id
            LEFT JOIN m_savings_account ta ON ta.id = d.to_savings_account_id
            LEFT JOIN m_client fc ON fc.id = d.from_client_id
            LEFT JOIN m_client tc ON tc.id = d.to_client_id
            LEFT JOIN m_portfolio_command_source cs
                   ON cs.entity_name = 'ACCOUNTTRANSFER' AND cs.action_name = 'CREATE' AND cs.resource_id = d.id
            WHERE t.transaction_type_enum IN (1, 2, 3, 4, 5, 7, 8, 17, 18, 19)
            """;

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;

    /**
     * A page of the account's transactions, newest first, from {@code fromDate} to {@code toDate} (both included),
     * optionally only credits or debits, before transaction {@code beforeId}.
     */
    public Map<String, Object> list(Long savingsId, LocalDate fromDate, LocalDate toDate, String type, Long beforeId, Integer limit) {
        context.authenticatedUser().validateHasReadPermission("SAVINGSACCOUNT");
        if (savingsId == null || fromDate == null || toDate == null || (type != null && !type.equals("CREDIT") && !type.equals("DEBIT"))) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.generalError(
                    "validation.msg.oneubank.transactions.query.invalid", "Send savingsId, fromDate and toDate; type is CREDIT or DEBIT")));
        }
        int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, MAX_LIMIT));
        StringBuilder sql = new StringBuilder(SELECT).append(" AND t.savings_account_id = ? AND t.transaction_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(savingsId, fromDate, toDate));
        if (type != null) {
            sql.append(" AND t.transaction_type_enum IN (")
                    .append(String.join(",", (type.equals("CREDIT") ? CREDITS : DEBITS).stream().map(String::valueOf).toList()))
                    .append(")");
        }
        if (beforeId != null) {
            sql.append(" AND t.id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        args.add(pageSize + 1);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", rows.stream().limit(pageSize).map(TransactionLookupService::transaction).toList());
        page.put("hasMore", rows.size() > pageSize);
        return page;
    }

    /** The transaction with this ID on this account, if it moves money. */
    public List<Map<String, Object>> one(Long savingsId, Long transactionId) {
        context.authenticatedUser().validateHasReadPermission("SAVINGSACCOUNT");
        return jdbcTemplate.queryForList(SELECT + " AND t.savings_account_id = ? AND t.id = ?", savingsId, transactionId).stream()
                .map(TransactionLookupService::transaction).toList();
    }

    /** Every transaction made with this reference: a debit's, or both sides of a transfer's. */
    public List<Map<String, Object>> byReference(String reference) {
        context.authenticatedUser().validateHasReadPermission("SAVINGSACCOUNT");
        return jdbcTemplate.queryForList(SELECT + " AND (t.external_id = ? OR cs.idempotency_key = ?) ORDER BY t.id", reference, reference)
                .stream().map(TransactionLookupService::transaction).toList();
    }

    static Map<String, Object> transaction(Map<String, Object> row) {
        int typeEnum = ((Number) row.get("transaction_type_enum")).intValue();
        boolean credit = CREDITS.contains(typeEnum);
        BigDecimal amount = (BigDecimal) row.get("amount");
        BigDecimal after = (BigDecimal) row.get("running_balance_derived");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("transactionId", row.get("id"));
        t.put("savingsId", row.get("savings_account_id"));
        t.put("accountNo", row.get("account_no"));
        t.put("clientId", row.get("client_id"));
        t.put("typeEnum", typeEnum);
        t.put("entryType", credit ? "CREDIT" : "DEBIT");
        t.put("amount", amount.toPlainString());
        t.put("balanceAfter", after == null ? null : after.toPlainString());
        t.put("balanceBefore", after == null ? null : (credit ? after.subtract(amount) : after.add(amount)).toPlainString());
        t.put("transactionDate", String.valueOf(row.get("transaction_date")));
        t.put("createdOn", timestamp(row.get("created_on_utc")));
        t.put("reversed", Boolean.TRUE.equals(row.get("is_reversed")));
        t.put("reversal", Boolean.TRUE.equals(row.get("is_reversal")));
        t.put("transactionReference", row.get("external_id") != null ? row.get("external_id") : row.get("transfer_reference"));
        t.put("paymentType", row.get("payment_type"));
        t.put("note", row.get("note"));
        t.put("virtualAccountType", row.get("va_type"));
        if (row.get("transfer_id") != null) {
            Map<String, Object> transfer = new LinkedHashMap<>();
            transfer.put("transferId", row.get("transfer_id"));
            transfer.put("fromAccountNo", row.get("from_account_no"));
            transfer.put("fromTransactionId", row.get("from_savings_transaction_id"));
            transfer.put("fromName", row.get("from_name"));
            transfer.put("toAccountNo", row.get("to_account_no"));
            transfer.put("toTransactionId", row.get("to_savings_transaction_id"));
            transfer.put("toName", row.get("to_name"));
            transfer.put("description", row.get("transfer_description"));
            t.put("transfer", transfer);
        }
        return t;
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

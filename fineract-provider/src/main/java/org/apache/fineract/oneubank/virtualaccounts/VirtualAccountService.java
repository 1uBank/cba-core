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
package org.apache.fineract.oneubank.virtualaccounts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.oneubank.savings.SavingsProductOneUBankParams;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Static virtual accounts. A virtual account is a savings account of the same customer as its parent account, opened on
 * the product set up for its type ({@code m_savings_product.virtual_account_type}). What makes it a virtual account is
 * in {@code va_details}: its type, its parent, the client's label and the reference it was created with. The name
 * payers see is {@code <owner's name> - <label>}.
 *
 * <p>
 * Creating, renaming and closing each happen in one transaction. A virtual account's balance, holds, blocks and
 * transactions are those of its savings account.
 */
@Service
@RequiredArgsConstructor
public class VirtualAccountService {

    static final String PARENT_ACCOUNT_NUMBER = "parentAccountNumber";
    static final String TYPE = "type";
    static final String LABEL = "label";
    static final String ACCOUNT_NUMBER = "accountNumber";
    static final String TRANSACTION_REFERENCE = "transactionReference";
    static final String AIRTIME = "AIRTIME";
    static final int MAX_PAGE = 100;

    private static final Set<String> CREATE_FIELDS = Set.of(PARENT_ACCOUNT_NUMBER, TYPE, LABEL, ACCOUNT_NUMBER, TRANSACTION_REFERENCE);
    private static final Pattern NUMBER = Pattern.compile("^\\d{10}$");
    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private static final String SELECT = """
            SELECT v.savings_account_id, sa.account_no, v.type, v.label, v.parent_savings_account_id,
                   p.account_no AS parent_account_no, sa.client_id, c.display_name, sa.status_enum,
                   v.transaction_reference, v.created_on_utc, v.updated_on_utc, v.closed_on_utc
            FROM va_details v
            JOIN m_savings_account sa ON sa.id = v.savings_account_id
            JOIN m_savings_account p ON p.id = v.parent_savings_account_id
            JOIN m_client c ON c.id = sa.client_id
            """;

    private final PlatformSecurityContext context;
    private final JdbcTemplate jdbcTemplate;
    private final SavingsApplicationProcessWritePlatformService applications;
    private final SavingsAccountWritePlatformService savingsAccounts;
    private final SavingsAccountRepository savingsAccountRepository;

    // Commands

    @Transactional
    public CommandProcessingResult create(JsonCommand command) {
        JsonObject body = object(command);
        unsupported(body, CREATE_FIELDS);
        String type = text(body, TYPE);
        String label = label(body);
        String accountNumber = text(body, ACCOUNT_NUMBER);
        String reference = text(body, TRANSACTION_REFERENCE);
        List<ApiParameterError> errors = new ArrayList<>();
        if (type == null || !SavingsProductOneUBankParams.VIRTUAL_ACCOUNT_TYPES.contains(type)) {
            errors.add(error("type.invalid", "`type` must be one of " + SavingsProductOneUBankParams.VIRTUAL_ACCOUNT_TYPES, TYPE));
        }
        if (AIRTIME.equals(type) && (accountNumber == null || !NUMBER.matcher(accountNumber).matches())) {
            errors.add(error("accountNumber.required",
                    "An AIRTIME virtual account needs `accountNumber`: the phone number without its leading 0", ACCOUNT_NUMBER));
        } else if (type != null && !AIRTIME.equals(type) && accountNumber != null) {
            errors.add(error("accountNumber.not.allowed", "`accountNumber` is only for AIRTIME virtual accounts", ACCOUNT_NUMBER));
        }
        if (reference != null && !REFERENCE.matcher(reference).matches()) {
            errors.add(error("transactionReference.invalid", "`transactionReference` must be 1 to 50 letters, digits, _ or -",
                    TRANSACTION_REFERENCE));
        }
        String parentNumber = text(body, PARENT_ACCOUNT_NUMBER);
        if (parentNumber == null) {
            errors.add(error("parentAccountNumber.required", "`parentAccountNumber` is required", PARENT_ACCOUNT_NUMBER));
        }
        throwIfAny(errors);

        AppUser user = context.authenticatedUser();
        user.validateHasCreatePermission("SAVINGSACCOUNT");
        user.validateHasPermissionTo("APPROVE_SAVINGSACCOUNT");
        user.validateHasPermissionTo("ACTIVATE_SAVINGSACCOUNT");

        Map<String, Object> parent = parent(parentNumber);
        Long productId = productFor(type)
                .orElseThrow(() -> new GeneralPlatformDomainRuleException("error.msg.oneubank.virtualaccount.type.unavailable",
                        "No savings product is set up for virtual accounts of type " + type, type));
        if (accountNumber != null && Boolean.TRUE.equals(jdbcTemplate
                .queryForObject("SELECT EXISTS (SELECT 1 FROM m_savings_account WHERE account_no = ?)", Boolean.class, accountNumber))) {
            throw new GeneralPlatformDomainRuleException("error.msg.savingsaccount.duplicate.accountNo",
                    "Account number " + accountNumber + " is already in use", accountNumber);
        }
        long clientId = ((Number) parent.get("client_id")).longValue();
        String today = DateUtils.getBusinessLocalDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT));

        JsonObject application = new JsonObject();
        application.addProperty("clientId", clientId);
        application.addProperty("productId", productId);
        application.addProperty("submittedOnDate", today);
        if (accountNumber != null) {
            application.addProperty("accountNo", accountNumber);
        }
        localised(application);
        Long savingsId = applications.submitApplication(JsonCommand.fromExistingCommand(command, application, clientId)).getSavingsId();
        if (savingsId == null && accountNumber != null) {
            // With a number supplied, Fineract's submit does not flush the new account, so it has no ID yet. Finding it
            // by number flushes it.
            savingsId = savingsAccountRepository.findSavingsAccountByAccountNumber(accountNumber).getId();
        }

        JsonObject approval = new JsonObject();
        approval.addProperty("approvedOnDate", today);
        localised(approval);
        applications.approveApplication(savingsId, JsonCommand.fromExistingCommand(command, approval, clientId));

        JsonObject activation = new JsonObject();
        activation.addProperty("activatedOnDate", today);
        localised(activation);
        savingsAccounts.activate(savingsId, JsonCommand.fromExistingCommand(command, activation, clientId));

        OffsetDateTime now = DateUtils.getAuditOffsetDateTime();
        jdbcTemplate.update("""
                INSERT INTO va_details (savings_account_id, type, parent_savings_account_id, label, transaction_reference,
                                        created_on_utc, updated_on_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", savingsId, type, parent.get("id"), label, reference, now, now);

        return result(command, savingsId, clientId);
    }

    @Transactional
    public CommandProcessingResult rename(Long savingsId, JsonCommand command) {
        JsonObject body = object(command);
        unsupported(body, Set.of(LABEL));
        String label = label(body);
        Map<String, Object> va = existing(savingsId);
        if (va.get("closed_on_utc") != null) {
            throw new GeneralPlatformDomainRuleException("error.msg.oneubank.virtualaccount.closed", "The virtual account is closed",
                    savingsId);
        }
        jdbcTemplate.update("UPDATE va_details SET label = ?, updated_on_utc = ? WHERE savings_account_id = ?", label,
                DateUtils.getAuditOffsetDateTime(), savingsId);
        return result(command, savingsId, ((Number) va.get("client_id")).longValue());
    }

    @Transactional
    public CommandProcessingResult close(Long savingsId, JsonCommand command) {
        Map<String, Object> va = existing(savingsId);
        long clientId = ((Number) va.get("client_id")).longValue();
        if (va.get("closed_on_utc") != null) {
            return result(command, savingsId, clientId);
        }
        context.authenticatedUser().validateHasPermissionTo("CLOSE_SAVINGSACCOUNT");
        Map<String, Object> account = jdbcTemplate.queryForMap("""
                SELECT COALESCE(account_balance_derived, 0) AS balance, COALESCE(on_hold_funds_derived, 0) AS on_hold,
                       sub_status_enum FROM m_savings_account WHERE id = ?""", savingsId);
        if (((BigDecimal) account.get("balance")).signum() != 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.oneubank.virtualaccount.not.empty",
                    "The virtual account still holds money; move it to the parent first", savingsId);
        }
        int subStatus = account.get("sub_status_enum") == null ? 0 : ((Number) account.get("sub_status_enum")).intValue();
        if (((BigDecimal) account.get("on_hold")).signum() != 0 || isBlocked(subStatus)) {
            throw new GeneralPlatformDomainRuleException("error.msg.oneubank.virtualaccount.restricted",
                    "The virtual account has a hold or block in place", savingsId);
        }
        JsonObject closing = new JsonObject();
        closing.addProperty("closedOnDate", DateUtils.getBusinessLocalDate().format(DateTimeFormatter.ofPattern(DATE_FORMAT)));
        localised(closing);
        savingsAccounts.close(savingsId, JsonCommand.fromExistingCommand(command, closing, clientId));
        jdbcTemplate.update("UPDATE va_details SET closed_on_utc = ?, updated_on_utc = ? WHERE savings_account_id = ?",
                DateUtils.getAuditOffsetDateTime(), DateUtils.getAuditOffsetDateTime(), savingsId);
        return result(command, savingsId, clientId);
    }

    // Reads

    /** The virtual account, if this savings account is one. */
    public Optional<Map<String, Object>> find(Long savingsId) {
        context.authenticatedUser().validateHasReadPermission("VIRTUALACCOUNT");
        return first(jdbcTemplate.queryForList(SELECT + " WHERE v.savings_account_id = ?", savingsId));
    }

    /** The virtual account with this number, if any. */
    public Optional<Map<String, Object>> findByNumber(String accountNumber) {
        context.authenticatedUser().validateHasReadPermission("VIRTUALACCOUNT");
        return first(jdbcTemplate.queryForList(SELECT + " WHERE sa.account_no = ?", accountNumber));
    }

    /** The virtual account created with this reference, if any. */
    public Optional<Map<String, Object>> findByReference(String transactionReference) {
        context.authenticatedUser().validateHasReadPermission("VIRTUALACCOUNT");
        return first(jdbcTemplate.queryForList(SELECT + " WHERE v.transaction_reference = ?", transactionReference));
    }

    /**
     * A page of the owner's virtual accounts (the owner of the parent's customer, in the Customer Owner datatable),
     * oldest first, optionally under one parent and of one type.
     */
    public Map<String, Object> list(String owner, Long parentSavingsId, String type, Long afterSavingsId, Integer limit) {
        context.authenticatedUser().validateHasReadPermission("VIRTUALACCOUNT");
        if (owner == null || owner.isBlank()) {
            throw new PlatformApiDataValidationException(List.of(error("owner.required", "`owner` is required", "owner")));
        }
        int pageSize = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_PAGE));
        StringBuilder sql = new StringBuilder(SELECT).append("""
                JOIN "Customer Owner" o ON o.client_id = sa.client_id
                WHERE o."Owner" = ? AND v.savings_account_id > ?""");
        List<Object> args = new ArrayList<>(List.of(owner, afterSavingsId == null ? 0L : afterSavingsId));
        if (parentSavingsId != null) {
            sql.append(" AND v.parent_savings_account_id = ?");
            args.add(parentSavingsId);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND v.type = ?");
            args.add(type);
        }
        sql.append(" ORDER BY v.savings_account_id LIMIT ?");
        args.add(pageSize + 1);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", rows.stream().limit(pageSize).map(VirtualAccountService::view).toList());
        page.put("hasMore", rows.size() > pageSize);
        return page;
    }

    // Helpers

    private Map<String, Object> parent(String accountNumber) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT sa.id, sa.client_id, sa.status_enum, EXISTS (SELECT 1 FROM va_details v WHERE v.savings_account_id = sa.id) AS is_va
                FROM m_savings_account sa WHERE sa.account_no = ?""", accountNumber);
        if (rows.isEmpty() || ((Number) rows.get(0).get("status_enum")).intValue() != SavingsAccountStatusType.ACTIVE.getValue()) {
            throw new GeneralPlatformDomainRuleException("error.msg.oneubank.virtualaccount.parent.not.active",
                    "The parent account does not exist or is not active", accountNumber);
        }
        if (Boolean.TRUE.equals(rows.get(0).get("is_va"))) {
            throw new GeneralPlatformDomainRuleException("error.msg.oneubank.virtualaccount.parent.is.virtual",
                    "A virtual account cannot be the parent of another", accountNumber);
        }
        return rows.get(0);
    }

    private Optional<Long> productFor(String type) {
        return jdbcTemplate.queryForList("SELECT id FROM m_savings_product WHERE virtual_account_type = ?", Long.class, type).stream()
                .findFirst();
    }

    /** The virtual account's row (database column names), for the commands. */
    private Map<String, Object> existing(Long savingsId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT + " WHERE v.savings_account_id = ?", savingsId);
        if (rows.isEmpty()) {
            throw new VirtualAccountNotFoundException(savingsId);
        }
        return rows.get(0);
    }

    private static boolean isBlocked(int subStatus) {
        // SavingsAccountSubStatusEnum: 400 BLOCK, 500 BLOCK_CREDIT, 600 BLOCK_DEBIT
        return subStatus == 400 || subStatus == 500 || subStatus == 600;
    }

    private static CommandProcessingResult result(JsonCommand command, Long savingsId, long clientId) {
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(savingsId) //
                .withSavingsId(savingsId) //
                .withClientId(clientId) //
                .build();
    }

    private static Optional<Map<String, Object>> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(view(rows.get(0)));
    }

    /** A virtual account as the API returns it. */
    static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> va = new LinkedHashMap<>();
        va.put("savingsId", row.get("savings_account_id"));
        va.put("accountNumber", row.get("account_no"));
        va.put("name", row.get("display_name") + " - " + row.get("label"));
        va.put("label", row.get("label"));
        va.put("type", row.get("type"));
        va.put("parentSavingsId", row.get("parent_savings_account_id"));
        va.put("parentAccountNumber", row.get("parent_account_no"));
        va.put("clientId", row.get("client_id"));
        va.put("status",
                row.get("closed_on_utc") != null
                        || ((Number) row.get("status_enum")).intValue() == SavingsAccountStatusType.CLOSED.getValue() ? "CLOSED"
                                : "ACTIVE");
        va.put("transactionReference", row.get("transaction_reference"));
        va.put("createdOn", timestamp(row.get("created_on_utc")));
        va.put("updatedOn", timestamp(row.get("updated_on_utc")));
        va.put("closedOn", timestamp(row.get("closed_on_utc")));
        return va;
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

    private static String label(JsonObject body) {
        String label = text(body, LABEL);
        if (label == null || label.length() > 50) {
            throw new PlatformApiDataValidationException(
                    List.of(error("label.invalid", "`label` is required, at most 50 characters", LABEL)));
        }
        return label;
    }

    private static JsonObject object(JsonCommand command) {
        JsonElement parsed = command.parsedJson();
        if (parsed == null || !parsed.isJsonObject()) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError
                    .generalError("validation.msg.oneubank.virtualaccount.body.invalid", "The request must be a JSON object")));
        }
        return parsed.getAsJsonObject();
    }

    private static void unsupported(JsonObject body, Set<String> known) {
        List<ApiParameterError> errors = new ArrayList<>();
        for (String name : body.keySet()) {
            if (!known.contains(name)) {
                errors.add(error("parameter.unsupported", "The parameter `" + name + "` is not supported", name));
            }
        }
        throwIfAny(errors);
    }

    private static String text(JsonObject body, String name) {
        JsonElement value = body.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    private static void localised(JsonObject json) {
        json.addProperty("locale", "en");
        json.addProperty("dateFormat", DATE_FORMAT);
    }

    private static void throwIfAny(List<ApiParameterError> errors) {
        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
    }

    private static ApiParameterError error(String code, String message, String parameter) {
        return ApiParameterError.parameterError("validation.msg.oneubank.virtualaccount." + code, message, parameter);
    }
}

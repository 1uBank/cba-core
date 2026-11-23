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
package org.apache.fineract.oneubank.customers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.dataqueries.service.DatatableWriteService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.address.service.AddressWritePlatformService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.service.ClientIdentifierWritePlatformService;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates a customer in one transaction: if any part is refused, for example a NIN already on another customer, nothing
 * is saved. Each part goes through Fineract's own service for it, so it is validated as on Fineract's own endpoint.
 *
 * <p>
 * The request has any of:
 * <ul>
 * <li>{@code client}: a {@code PUT /clients/{id}} body (names, date of birth, email, mobile number, ...);</li>
 * <li>{@code identifiers}: {@code POST /clients/{id}/identifiers} bodies; an identifier of a type the customer already
 * has replaces it, any other is added;</li>
 * <li>{@code address}: a {@code POST /client/{id}/addresses} body with its {@code addressTypeId}; it replaces the
 * customer's address of that type as a whole (fields not sent are cleared), or is added if they have none;</li>
 * <li>{@code datatableRows}: {@code [{"registeredTableName": ..., "rows": [<row>, ...]}]}; the rows replace all of the
 * customer's rows in that client datatable (for example Related Persons).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CustomerUpdateService {

    static final String CLIENT = "client";
    static final String IDENTIFIERS = "identifiers";
    static final String ADDRESS = "address";
    static final String DATATABLE_ROWS = "datatableRows";
    private static final Set<String> SUPPORTED = Set.of(CLIENT, IDENTIFIERS, ADDRESS, DATATABLE_ROWS);

    /** Address fields a replacement clears when they are not sent, and their columns. */
    private static final Map<String, String> CLEARABLE_ADDRESS_FIELDS = clearableAddressFields();

    private final PlatformSecurityContext context;
    private final ClientRepositoryWrapper clientRepository;
    private final ClientWritePlatformService clientWritePlatformService;
    private final ClientIdentifierWritePlatformService clientIdentifierWritePlatformService;
    private final AddressWritePlatformService addressWritePlatformService;
    private final DatatableWriteService datatableWriteService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public CommandProcessingResult update(Long clientId, JsonCommand command) {
        JsonObject body = validate(command);
        AppUser user = context.authenticatedUser();
        Client client = clientRepository.findOneWithNotFoundDetection(clientId);
        Map<String, Object> changes = new LinkedHashMap<>();

        if (body.has(CLIENT)) {
            user.validateHasUpdatePermission("CLIENT");
            CommandProcessingResult result = clientWritePlatformService.updateClient(clientId,
                    JsonCommand.fromExistingCommand(command, body.get(CLIENT), clientId));
            if (result != null && result.getChanges() != null && !result.getChanges().isEmpty()) {
                changes.put(CLIENT, result.getChanges().keySet());
            }
        }
        if (body.has(IDENTIFIERS)) {
            changes.put(IDENTIFIERS, updateIdentifiers(user, clientId, body, command));
        }
        if (body.has(ADDRESS)) {
            changes.put(ADDRESS, replaceAddress(user, clientId, body.getAsJsonObject(ADDRESS), command));
        }
        if (body.has(DATATABLE_ROWS)) {
            for (JsonElement table : body.getAsJsonArray(DATATABLE_ROWS)) {
                JsonObject t = table.getAsJsonObject();
                String name = t.get("registeredTableName").getAsString();
                changes.put(name, replaceRows(user, clientId, name, t.getAsJsonArray("rows"), command));
            }
        }

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(clientId) //
                .withOfficeId(client.officeId()) //
                .withClientId(clientId) //
                .with(changes) //
                .build();
    }

    private List<String> updateIdentifiers(AppUser user, Long clientId, JsonObject body, JsonCommand command) {
        List<String> done = new ArrayList<>();
        for (JsonElement identifier : body.getAsJsonArray(IDENTIFIERS)) {
            long typeId = identifier.getAsJsonObject().get("documentTypeId").getAsLong();
            List<Long> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM m_client_identifier WHERE client_id = ? AND document_type_id = ? ORDER BY id", Long.class, clientId,
                    typeId);
            if (existing.isEmpty()) {
                user.validateHasCreatePermission("CLIENTIDENTIFIER");
                clientIdentifierWritePlatformService.addClientIdentifier(clientId,
                        JsonCommand.fromExistingCommand(command, identifier, clientId));
                done.add("added type " + typeId);
            } else {
                user.validateHasUpdatePermission("CLIENTIDENTIFIER");
                // Fineract's identifier update reads the status as the enum name (ACTIVE), its create as Active.
                JsonObject update = identifier.getAsJsonObject().deepCopy();
                JsonElement status = update.get("status");
                if (status != null && status.isJsonPrimitive()) {
                    update.addProperty("status", status.getAsString().toUpperCase(Locale.ROOT));
                }
                clientIdentifierWritePlatformService.updateClientIdentifier(clientId, existing.get(0),
                        JsonCommand.fromExistingCommand(command, update, clientId));
                done.add("replaced type " + typeId);
            }
        }
        return done;
    }

    private String replaceAddress(AppUser user, Long clientId, JsonObject address, JsonCommand command) {
        long typeId = address.get("addressTypeId").getAsLong();
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT address_id FROM m_client_address WHERE client_id = ? AND address_type_id = ? ORDER BY id", Long.class, clientId,
                typeId);
        if (existing.isEmpty()) {
            user.validateHasCreatePermission("ADDRESS");
            addressWritePlatformService.addClientAddress(clientId, typeId, JsonCommand.fromExistingCommand(command, address, clientId));
            return "added";
        }
        user.validateHasUpdatePermission("ADDRESS");
        Long addressId = existing.get(0);
        // Fineract's address update takes the address by its ID and refuses its type.
        JsonObject update = address.deepCopy();
        update.remove("addressTypeId");
        update.addProperty("addressId", addressId);
        addressWritePlatformService.updateClientAddress(clientId, JsonCommand.fromExistingCommand(command, update, clientId));
        // Fineract's address update leaves fields that are not sent as they were; a replacement clears them.
        for (Map.Entry<String, String> field : CLEARABLE_ADDRESS_FIELDS.entrySet()) {
            JsonElement value = address.get(field.getKey());
            if (value == null || value.isJsonNull() || (value.isJsonPrimitive() && value.getAsString().isBlank())) {
                jdbcTemplate.update("UPDATE m_address SET " + field.getValue() + " = NULL WHERE id = ?", addressId); // NOSONAR
            }
        }
        return "replaced";
    }

    private int replaceRows(AppUser user, Long clientId, String table, Iterable<JsonElement> rows, JsonCommand command) {
        Integer clientTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM x_registered_table WHERE registered_table_name = ? AND application_table_name = 'm_client'",
                Integer.class, table);
        if (clientTables == null || clientTables == 0) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.parameterError(
                    "validation.msg.oneubank.customer.datatable.unknown", "`" + table + "` is not a customer datatable", DATATABLE_ROWS)));
        }
        user.validateHasPermissionTo("DELETE_" + table);
        user.validateHasPermissionTo("CREATE_" + table);
        datatableWriteService.deleteDatatableEntries(table, clientId, command);
        int count = 0;
        for (JsonElement row : rows) {
            datatableWriteService.createNewDatatableEntry(table, clientId, row.toString());
            count++;
        }
        return count;
    }

    private static JsonObject validate(JsonCommand command) {
        List<ApiParameterError> errors = new ArrayList<>();
        JsonElement parsed = command.parsedJson();
        if (parsed == null || !parsed.isJsonObject() || parsed.getAsJsonObject().isEmpty()) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.generalError(
                    "validation.msg.oneubank.customer.body.invalid", "The request must be a JSON object with something to change")));
        }
        JsonObject body = parsed.getAsJsonObject();
        for (String name : body.keySet()) {
            if (!SUPPORTED.contains(name)) {
                errors.add(error("parameter.unsupported", "The parameter `" + name + "` is not supported", name));
            }
        }
        if (body.has(CLIENT) && !body.get(CLIENT).isJsonObject()) {
            errors.add(error("client.invalid", "`client` must be an object", CLIENT));
        }
        if (body.has(IDENTIFIERS) && !allObjectsWith(body.get(IDENTIFIERS), "documentTypeId")) {
            errors.add(
                    error("identifiers.invalid", "`identifiers` must be an array of objects, each with a `documentTypeId`", IDENTIFIERS));
        }
        if (body.has(ADDRESS) && !(body.get(ADDRESS).isJsonObject() && hasNumber(body.getAsJsonObject(ADDRESS), "addressTypeId"))) {
            errors.add(error("address.invalid", "`address` must be an object with an `addressTypeId`", ADDRESS));
        }
        if (body.has(DATATABLE_ROWS) && !validRowSets(body.get(DATATABLE_ROWS))) {
            errors.add(error("datatableRows.invalid",
                    "`datatableRows` must be an array of {\"registeredTableName\": <name>, \"rows\": [<object>, ...]}", DATATABLE_ROWS));
        }
        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
        return body;
    }

    private static boolean allObjectsWith(JsonElement array, String numberField) {
        if (!array.isJsonArray()) {
            return false;
        }
        for (JsonElement e : array.getAsJsonArray()) {
            if (!e.isJsonObject() || !hasNumber(e.getAsJsonObject(), numberField)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validRowSets(JsonElement sets) {
        if (!sets.isJsonArray()) {
            return false;
        }
        for (JsonElement set : sets.getAsJsonArray()) {
            if (!set.isJsonObject()) {
                return false;
            }
            JsonObject s = set.getAsJsonObject();
            JsonElement name = s.get("registeredTableName");
            JsonElement rows = s.get("rows");
            if (name == null || !name.isJsonPrimitive() || name.getAsString().isBlank() || rows == null || !rows.isJsonArray()) {
                return false;
            }
            for (JsonElement row : rows.getAsJsonArray()) {
                if (!row.isJsonObject()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasNumber(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static ApiParameterError error(String code, String message, String parameter) {
        return ApiParameterError.parameterError("validation.msg.oneubank.customer." + code, message, parameter);
    }

    private static Map<String, String> clearableAddressFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("street", "street");
        fields.put("addressLine2", "address_line_2");
        fields.put("addressLine3", "address_line_3");
        fields.put("townVillage", "town_village");
        fields.put("city", "city");
        fields.put("countyDistrict", "county_district");
        fields.put("stateProvinceId", "state_province_id");
        fields.put("postalCode", "postal_code");
        return Map.copyOf(fields);
    }
}

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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.service.ClientIdentifierWritePlatformService;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a customer with everything the bank records about them in one transaction: Fineract's own client creation
 * (names, address, datatables, and the first savings account opened and activated through {@code savingsProductId}),
 * then Fineract's own identifier creation for each identifier sent (BVN, NIN, CAC registration number). If any part is
 * refused, for example a BVN already on another customer, nothing is saved.
 *
 * <p>
 * The request is {@code {"client": <POST /clients body>, "identifiers": [<POST /clients/{id}/identifiers body>, ...]}}:
 * each part is validated exactly as Fineract validates it on its own endpoint.
 */
@Service
@RequiredArgsConstructor
public class CustomerOnboardingService {

    static final String CLIENT = "client";
    static final String IDENTIFIERS = "identifiers";
    private static final Set<String> SUPPORTED = Set.of(CLIENT, IDENTIFIERS);

    private final PlatformSecurityContext context;
    private final ClientWritePlatformService clientWritePlatformService;
    private final ClientIdentifierWritePlatformService clientIdentifierWritePlatformService;

    @Transactional
    public CommandProcessingResult create(JsonCommand command) {
        // The command's own permission lets a caller use this call; the parts still need their usual permissions.
        context.authenticatedUser().validateHasCreatePermission("CLIENT");
        JsonObject body = validate(command);
        JsonArray identifiers = body.has(IDENTIFIERS) ? body.getAsJsonArray(IDENTIFIERS) : new JsonArray();
        if (!identifiers.isEmpty()) {
            context.authenticatedUser().validateHasCreatePermission("CLIENTIDENTIFIER");
        }

        CommandProcessingResult client = clientWritePlatformService
                .createClient(JsonCommand.fromExistingCommand(command, body.getAsJsonObject(CLIENT)));
        Long clientId = client.getClientId();
        for (JsonElement identifier : identifiers) {
            clientIdentifierWritePlatformService.addClientIdentifier(clientId,
                    JsonCommand.fromExistingCommand(command, identifier, clientId));
        }

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(clientId) //
                .withEntityExternalId(client.getResourceExternalId()) //
                .withOfficeId(client.getOfficeId()) //
                .withClientId(clientId) //
                .withSavingsId(client.getSavingsId()) //
                .with(Map.of("identifiers", identifiers.size())) //
                .build();
    }

    private static JsonObject validate(JsonCommand command) {
        List<ApiParameterError> errors = new ArrayList<>();
        JsonElement parsed = command.parsedJson();
        if (parsed == null || !parsed.isJsonObject()) {
            errors.add(
                    ApiParameterError.generalError("validation.msg.oneubank.customer.body.invalid", "The request must be a JSON object"));
            throw new PlatformApiDataValidationException(errors);
        }
        JsonObject body = parsed.getAsJsonObject();
        for (String name : body.keySet()) {
            if (!SUPPORTED.contains(name)) {
                errors.add(ApiParameterError.parameterError("validation.msg.oneubank.customer.parameter.unsupported",
                        "The parameter `" + name + "` is not supported", name));
            }
        }
        if (!body.has(CLIENT) || !body.get(CLIENT).isJsonObject()) {
            errors.add(ApiParameterError.parameterError("validation.msg.oneubank.customer.client.mandatory",
                    "The parameter `client` is mandatory and must be an object", CLIENT));
        }
        if (body.has(IDENTIFIERS)) {
            JsonElement identifiers = body.get(IDENTIFIERS);
            if (!identifiers.isJsonArray()) {
                errors.add(ApiParameterError.parameterError("validation.msg.oneubank.customer.identifiers.invalid",
                        "The parameter `identifiers` must be an array", IDENTIFIERS));
            } else {
                for (JsonElement identifier : identifiers.getAsJsonArray()) {
                    if (!identifier.isJsonObject()) {
                        errors.add(ApiParameterError.parameterError("validation.msg.oneubank.customer.identifiers.invalid",
                                "Each identifier must be an object", IDENTIFIERS));
                        break;
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
        return body;
    }
}

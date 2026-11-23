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
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.exception.SavingsProductNotFoundException;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens another savings account for a customer, approved and active, in one transaction: the same step Fineract uses
 * when a client is created with a {@code savingsProductId}. The account gets its number from the product (a NUBAN when
 * the product has a prefix).
 *
 * <p>
 * The request is {@code {"productId": <savings product ID>}}.
 */
@Service
@RequiredArgsConstructor
public class CustomerAccountService {

    static final String PRODUCT_ID = "productId";
    private static final Set<String> SUPPORTED = Set.of(PRODUCT_ID);

    private final PlatformSecurityContext context;
    private final ClientRepositoryWrapper clientRepository;
    private final SavingsProductRepository savingsProductRepository;
    private final SavingsApplicationProcessWritePlatformService savingsApplicationService;

    @Transactional
    public CommandProcessingResult open(Long clientId, JsonCommand command) {
        long productId = validate(command);
        AppUser user = context.authenticatedUser();
        // The command's own permission lets a caller use this call; the steps still need their usual permissions.
        user.validateHasCreatePermission("SAVINGSACCOUNT");
        user.validateHasPermissionTo("APPROVE_SAVINGSACCOUNT");
        user.validateHasPermissionTo("ACTIVATE_SAVINGSACCOUNT");

        Client client = clientRepository.findOneWithNotFoundDetection(clientId);
        if (!client.isActive()) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.generalError(
                    "validation.msg.oneubank.account.client.not.active", "Accounts can only be opened for an active customer")));
        }
        // Fineract's one-step opening assumes the product exists; an unknown one gets the usual "not found" here.
        if (!savingsProductRepository.existsById(productId)) {
            throw new SavingsProductNotFoundException(productId);
        }
        CommandProcessingResult opened = savingsApplicationService
                .createActiveApplication(new SavingsAccountDataDTO(client, null, productId, DateUtils.getBusinessLocalDate(), user, null));

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(opened.getSavingsId()) //
                .withOfficeId(client.officeId()) //
                .withClientId(clientId) //
                .withSavingsId(opened.getSavingsId()) //
                .setRollbackTransaction(opened.isRollbackTransaction()) //
                .build();
    }

    private static long validate(JsonCommand command) {
        JsonElement parsed = command.parsedJson();
        if (parsed == null || !parsed.isJsonObject()) {
            throw invalid("body.invalid", "The request must be a JSON object", null);
        }
        JsonObject body = parsed.getAsJsonObject();
        for (String name : body.keySet()) {
            if (!SUPPORTED.contains(name)) {
                throw invalid("parameter.unsupported", "The parameter `" + name + "` is not supported", name);
            }
        }
        JsonElement productId = body.get(PRODUCT_ID);
        if (productId == null || !productId.isJsonPrimitive() || !productId.getAsJsonPrimitive().isNumber() || productId.getAsLong() <= 0) {
            throw invalid("productId.invalid", "`productId` is mandatory and must be a savings product ID", PRODUCT_ID);
        }
        return productId.getAsLong();
    }

    private static PlatformApiDataValidationException invalid(String code, String message, String parameter) {
        return new PlatformApiDataValidationException(
                List.of(ApiParameterError.parameterError("validation.msg.oneubank.account." + code, message, parameter)));
    }
}

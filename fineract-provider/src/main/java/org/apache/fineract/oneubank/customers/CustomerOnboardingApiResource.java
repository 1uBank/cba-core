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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.springframework.stereotype.Component;

/**
 * 1UBank: creates a customer, their identifiers and their first account in one transaction, for the Banking API, and
 * updates them in one transaction, opens further accounts, and finds customers by identity number, email or phone.
 * Creation goes through Fineract's command pipeline, so it is permission-checked ({@code CREATE_ONEUBANKCUSTOMER}),
 * audited and idempotent ({@code Idempotency-Key}). See {@link CustomerOnboardingService}.
 */
@Path("/v1/oneubank/customers")
@Component
@RequiredArgsConstructor
public class CustomerOnboardingApiResource {

    static final String ENTITY = "ONEUBANKCUSTOMER";
    static final String ACCOUNT_ENTITY = "ONEUBANKACCOUNT";

    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
    private final ToApiJsonSerializer<Map<String, Object>> searchSerializer;
    private final CustomerSearchService customerSearchService;

    /**
     * @return {@code clientId}, {@code savingsId} (the first account), {@code officeId} and {@code resourceId} (the
     *         client)
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String create(final String apiRequestBodyAsJson) {
        CommandWrapper command = new CommandWrapper(null, null, null, null, null, "CREATE", ENTITY, null, null, "/oneubank/customers",
                apiRequestBodyAsJson, null, null, null, null, null, null, null, null, null);
        return toApiJsonSerializer.serialize(commandsSourceWritePlatformService.logCommandSource(command));
    }

    /**
     * Updates a customer in one transaction: see {@link CustomerUpdateService#update}. Goes through the command
     * pipeline ({@code UPDATE_ONEUBANKCUSTOMER}), like creation.
     */
    @PUT
    @Path("{clientId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String update(@PathParam("clientId") final Long clientId, final String apiRequestBodyAsJson) {
        CommandWrapper command = new CommandWrapper(null, null, clientId, null, null, "UPDATE", ENTITY, clientId, null,
                "/oneubank/customers/" + clientId, apiRequestBodyAsJson, null, null, null, null, null, null, null, null, null);
        return toApiJsonSerializer.serialize(commandsSourceWritePlatformService.logCommandSource(command));
    }

    /**
     * Opens another account for a customer, approved and active, in one transaction: see
     * {@link CustomerAccountService#open}. Goes through the command pipeline ({@code CREATE_ONEUBANKACCOUNT}), so it is
     * audited and idempotent ({@code Idempotency-Key}).
     *
     * @return {@code savingsId} (the new account), {@code clientId} and {@code officeId}
     */
    @POST
    @Path("{clientId}/accounts")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String openAccount(@PathParam("clientId") final Long clientId, final String apiRequestBodyAsJson) {
        CommandWrapper command = new CommandWrapper(null, null, clientId, null, null, "CREATE", ACCOUNT_ENTITY, null, null,
                "/oneubank/customers/" + clientId + "/accounts", apiRequestBodyAsJson, null, null, null, null, null, null, null, null,
                null);
        return toApiJsonSerializer.serialize(commandsSourceWritePlatformService.logCommandSource(command));
    }

    /**
     * Finds the owner's customers by BVN, NIN, email and/or mobile number: see {@link CustomerSearchService#search}.
     * Read-only, so a POST only to keep identity numbers out of URLs.
     */
    @POST
    @Path("search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String search(final String apiRequestBodyAsJson) {
        return searchSerializer.serialize(customerSearchService.search(apiRequestBodyAsJson));
    }
}

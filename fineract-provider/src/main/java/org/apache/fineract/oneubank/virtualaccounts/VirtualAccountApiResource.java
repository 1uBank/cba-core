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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.springframework.stereotype.Component;

/**
 * 1UBank: static virtual accounts, for the Banking API and the portal. Creating, renaming and closing go through
 * Fineract's command pipeline ({@code CREATE_}, {@code UPDATE_}, {@code CLOSE_VIRTUALACCOUNT}), so they are
 * permission-checked, audited and idempotent ({@code Idempotency-Key}). See {@link VirtualAccountService}.
 */
@Path("/v1/oneubank/virtual-accounts")
@Component
@RequiredArgsConstructor
public class VirtualAccountApiResource {

    static final String ENTITY = "VIRTUALACCOUNT";

    private final PortfolioCommandSourceWritePlatformService commands;
    private final DefaultToApiJsonSerializer<CommandProcessingResult> resultSerializer;
    private final ToApiJsonSerializer<Map<String, Object>> serializer;
    private final VirtualAccountService virtualAccounts;

    /**
     * {@code {"parentAccountNumber", "type", "label", "accountNumber" (AIRTIME only), "transactionReference"}}.
     *
     * @return {@code savingsId} (the virtual account) and {@code clientId}
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String create(final String json) {
        return run(null, "CREATE", "/oneubank/virtual-accounts", json);
    }

    /** {@code {"label"}}. */
    @PUT
    @Path("{savingsId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String rename(@PathParam("savingsId") final Long savingsId, final String json) {
        return run(savingsId, "UPDATE", "/oneubank/virtual-accounts/" + savingsId, json);
    }

    @POST
    @Path("{savingsId}/close")
    @Produces(MediaType.APPLICATION_JSON)
    public String close(@PathParam("savingsId") final Long savingsId) {
        return run(savingsId, "CLOSE", "/oneubank/virtual-accounts/" + savingsId + "/close", "{}");
    }

    @GET
    @Path("{savingsId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String get(@PathParam("savingsId") final Long savingsId) {
        return serializer.serialize(virtualAccounts.find(savingsId).orElseThrow(() -> new VirtualAccountNotFoundException(savingsId)));
    }

    /**
     * One virtual account by {@code accountNumber} or {@code transactionReference}; otherwise a page of the
     * {@code owner}'s, optionally by {@code parentSavingsId} and {@code type}: {@code {"items": [...], "hasMore"}}.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String find(@QueryParam("accountNumber") final String accountNumber,
            @QueryParam("transactionReference") final String transactionReference, @QueryParam("owner") final String owner,
            @QueryParam("parentSavingsId") final Long parentSavingsId, @QueryParam("type") final String type,
            @QueryParam("afterSavingsId") final Long afterSavingsId, @QueryParam("limit") final Integer limit) {
        if (accountNumber != null || transactionReference != null) {
            Optional<Map<String, Object>> va = accountNumber != null ? virtualAccounts.findByNumber(accountNumber)
                    : virtualAccounts.findByReference(transactionReference);
            return serializer.serialize(va
                    .orElseThrow(() -> new VirtualAccountNotFoundException(accountNumber != null ? accountNumber : transactionReference)));
        }
        return serializer.serialize(virtualAccounts.list(owner, parentSavingsId, type, afterSavingsId, limit));
    }

    private String run(Long savingsId, String action, String href, String json) {
        CommandWrapper command = new CommandWrapper(null, null, null, null, savingsId, action, ENTITY, savingsId, null, href, json, null,
                null, null, null, null, null, null, null, null);
        return resultSerializer.serialize(commands.logCommandSource(command));
    }
}

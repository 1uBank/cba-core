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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.springframework.stereotype.Component;

/**
 * 1UBank: savings transactions with their reference, transfer sides and narration. Read-only; needs
 * {@code READ_SAVINGSACCOUNT}.
 * <ul>
 * <li>{@code ?savingsId=&fromDate=&toDate=[&type=CREDIT|DEBIT][&beforeId=][&limit=]}: a page, newest first;</li>
 * <li>{@code ?savingsId=&transactionId=}: one transaction ({@code items} empty if none);</li>
 * <li>{@code ?transactionReference=}: every transaction made with the reference.</li>
 * </ul>
 */
@Path("/v1/oneubank/transactions")
@Component
@RequiredArgsConstructor
public class TransactionLookupApiResource {

    private final TransactionLookupService transactions;
    private final ToApiJsonSerializer<Map<String, Object>> serializer;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String find(@QueryParam("savingsId") final Long savingsId, @QueryParam("transactionId") final Long transactionId,
            @QueryParam("transactionReference") final String transactionReference, @QueryParam("fromDate") final String fromDate,
            @QueryParam("toDate") final String toDate, @QueryParam("type") final String type, @QueryParam("beforeId") final Long beforeId,
            @QueryParam("limit") final Integer limit) {
        if (transactionReference != null) {
            return serializer.serialize(Map.of("items", transactions.byReference(transactionReference), "hasMore", false));
        }
        if (transactionId != null) {
            return serializer.serialize(Map.of("items", transactions.one(savingsId, transactionId), "hasMore", false));
        }
        return serializer.serialize(transactions.list(savingsId, fromDate == null ? null : LocalDate.parse(fromDate),
                toDate == null ? null : LocalDate.parse(toDate), type, beforeId, limit));
    }
}

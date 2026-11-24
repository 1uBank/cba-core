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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.springframework.stereotype.Component;

/**
 * 1UBank: finds a hold by the reference it was placed with ({@code ?transactionReference=}) or by account and
 * transaction ID ({@code ?savingsId=&transactionId=}). Read-only; needs {@code READ_SAVINGSACCOUNT}. Holds are placed
 * and released with Fineract's own {@code holdAmount} and {@code releaseAmount} commands.
 */
@Path("/v1/oneubank/holds")
@Component
@RequiredArgsConstructor
public class HoldLookupApiResource {

    private final HoldLookupService holds;
    private final ToApiJsonSerializer<Map<String, Object>> serializer;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String find(@QueryParam("transactionReference") final String transactionReference, @QueryParam("savingsId") final Long savingsId,
            @QueryParam("transactionId") final Long transactionId) {
        Optional<Map<String, Object>> hold;
        if (transactionReference != null && !transactionReference.isBlank() && savingsId == null && transactionId == null) {
            hold = holds.byReference(transactionReference);
        } else if (transactionReference == null && savingsId != null && transactionId != null) {
            hold = holds.byTransaction(savingsId, transactionId);
        } else {
            throw HoldLookupService.invalidQuery();
        }
        return serializer.serialize(hold.orElseThrow(HoldNotFoundException::new));
    }
}

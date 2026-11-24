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
package org.apache.fineract.oneubank.transfers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.springframework.stereotype.Component;

/**
 * 1UBank: an account transfer by the reference it was made with ({@code ?transactionReference=}): the reference is the
 * {@code Idempotency-Key} sent with Fineract's own {@code POST /accounttransfers}. Read-only; needs
 * {@code READ_ACCOUNTTRANSFER}.
 */
@Path("/v1/oneubank/transfers")
@Component
@RequiredArgsConstructor
public class TransferLookupApiResource {

    private final TransferLookupService transfers;
    private final ToApiJsonSerializer<Map<String, Object>> serializer;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String find(@QueryParam("transactionReference") final String transactionReference) {
        return serializer.serialize(
                transfers.byReference(transactionReference).orElseThrow(() -> new TransferNotFoundException(transactionReference)));
    }
}

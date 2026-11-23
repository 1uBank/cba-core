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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.exception.DuplicateClientIdentifierException;
import org.apache.fineract.portfolio.client.service.ClientIdentifierWritePlatformService;
import org.apache.fineract.portfolio.client.service.ClientWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerOnboardingServiceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private ClientWritePlatformService clients;
    @Mock
    private ClientIdentifierWritePlatformService identifiers;

    private CustomerOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new CustomerOnboardingService(context, clients, identifiers);
    }

    private static JsonCommand command(String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), new FromJsonHelper(), "ONEUBANKCUSTOMER", null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private void signedIn() {
        when(context.authenticatedUser()).thenReturn(mock(AppUser.class));
    }

    @Test
    void createsTheClientThenEachIdentifierForIt() {
        signedIn();
        when(clients.createClient(any()))
                .thenReturn(new CommandProcessingResultBuilder().withClientId(10L).withSavingsId(158L).withOfficeId(1L).build());

        CommandProcessingResult result = service.create(command("""
                {"client": {"firstname": "Ada", "lastname": "Obi", "savingsProductId": 3},
                 "identifiers": [{"documentTypeId": 1, "documentKey": "22200000001"},
                                 {"documentTypeId": 2, "documentKey": "12345678901"}]}"""));

        assertThat(result.getClientId()).isEqualTo(10L);
        assertThat(result.getSavingsId()).isEqualTo(158L);
        ArgumentCaptor<JsonCommand> client = ArgumentCaptor.forClass(JsonCommand.class);
        ArgumentCaptor<JsonCommand> identifier = ArgumentCaptor.forClass(JsonCommand.class);
        InOrder order = inOrder(clients, identifiers);
        order.verify(clients).createClient(client.capture());
        order.verify(identifiers, org.mockito.Mockito.times(2)).addClientIdentifier(eq(10L), identifier.capture());
        assertThat(client.getValue().stringValueOfParameterNamed("firstname")).isEqualTo("Ada");
        List<String> keys = new ArrayList<>();
        identifier.getAllValues().forEach(c -> keys.add(c.stringValueOfParameterNamed("documentKey")));
        assertThat(keys).containsExactly("22200000001", "12345678901");
        assertThat(identifier.getValue().getClientId()).isEqualTo(10L);
    }

    @Test
    void identifiersAreOptional() {
        signedIn();
        when(clients.createClient(any())).thenReturn(new CommandProcessingResultBuilder().withClientId(10L).build());

        service.create(command("{\"client\": {\"fullname\": \"Seed Business Ltd\"}}"));

        verifyNoInteractions(identifiers);
    }

    @Test
    void aRefusedIdentifierFailsTheWholeCall() {
        signedIn();
        when(clients.createClient(any())).thenReturn(new CommandProcessingResultBuilder().withClientId(10L).build());
        when(identifiers.addClientIdentifier(eq(10L), any())).thenThrow(new DuplicateClientIdentifierException("BVN"));

        // Thrown out of the @Transactional method, so the client created first is rolled back with it.
        assertThatThrownBy(() -> service.create(command("""
                {"client": {"firstname": "Ada"}, "identifiers": [{"documentTypeId": 1, "documentKey": "22200000001"}]}""")))
                .isInstanceOf(DuplicateClientIdentifierException.class);
    }

    @Test
    void refusesARequestWithoutAClientOrWithUnknownParts() {
        signedIn();

        assertThatThrownBy(() -> service.create(command("{\"identifiers\": []}"))).isInstanceOf(PlatformApiDataValidationException.class);
        assertThatThrownBy(() -> service.create(command("{\"client\": {}, \"accounts\": []}")))
                .isInstanceOf(PlatformApiDataValidationException.class);
        assertThatThrownBy(() -> service.create(command("{\"client\": {}, \"identifiers\": {}}")))
                .isInstanceOf(PlatformApiDataValidationException.class);
        verify(clients, never()).createClient(any());
    }
}

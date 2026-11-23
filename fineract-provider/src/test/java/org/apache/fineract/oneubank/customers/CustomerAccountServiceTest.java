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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.savings.data.SavingsAccountDataDTO;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.exception.SavingsProductNotFoundException;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerAccountServiceTest {

    private static final long CLIENT_ID = 10L;

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private ClientRepositoryWrapper clientRepository;
    @Mock
    private SavingsProductRepository products;
    @Mock
    private SavingsApplicationProcessWritePlatformService savings;
    @Mock
    private AppUser user;
    @Mock
    private Client client;

    private CustomerAccountService service;

    @BeforeEach
    void setUp() {
        service = new CustomerAccountService(context, clientRepository, products, savings);
        lenient().when(context.authenticatedUser()).thenReturn(user);
        lenient().when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
        lenient().when(client.officeId()).thenReturn(1L);
        lenient().when(products.existsById(3L)).thenReturn(true);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 11, 23))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    private static JsonCommand command(String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), new FromJsonHelper(), "ONEUBANKACCOUNT", null, null, null, CLIENT_ID,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void opensAnActiveAccountOnTheProductForTheCustomer() {
        when(client.isActive()).thenReturn(true);
        when(savings.createActiveApplication(any())).thenReturn(new CommandProcessingResultBuilder().withSavingsId(158L).build());

        var result = service.open(CLIENT_ID, command("{\"productId\": 3}"));

        ArgumentCaptor<SavingsAccountDataDTO> sent = ArgumentCaptor.forClass(SavingsAccountDataDTO.class);
        verify(savings).createActiveApplication(sent.capture());
        assertThat(sent.getValue().getClient()).isSameAs(client);
        assertThat(sent.getValue().getSavingsProduct()).isEqualTo(3L);
        assertThat(sent.getValue().getAppliedBy()).isSameAs(user);
        assertThat(sent.getValue().getApplicationDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(result.getSavingsId()).isEqualTo(158L);
        assertThat(result.getClientId()).isEqualTo(CLIENT_ID);
    }

    @Test
    void refusesAnUnknownProduct() {
        when(client.isActive()).thenReturn(true);

        assertThatThrownBy(() -> service.open(CLIENT_ID, command("{\"productId\": 99}")))
                .isInstanceOf(SavingsProductNotFoundException.class);
        verifyNoInteractions(savings);
    }

    @Test
    void refusesAnInactiveCustomer() {
        when(client.isActive()).thenReturn(false);

        assertThatThrownBy(() -> service.open(CLIENT_ID, command("{\"productId\": 3}")))
                .isInstanceOf(PlatformApiDataValidationException.class);
        verifyNoInteractions(savings);
    }

    @Test
    void needsThePermissionsOfEachStep() {
        lenient().doThrow(new NoAuthorizationException("no")).when(user).validateHasPermissionTo("ACTIVATE_SAVINGSACCOUNT");

        assertThatThrownBy(() -> service.open(CLIENT_ID, command("{\"productId\": 3}"))).isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(savings);
    }

    @Test
    void refusesAMalformedRequest() {
        for (String body : List.of("[]", "{}", "{\"productId\": \"3\"}", "{\"productId\": 0}", "{\"productId\": 3, \"other\": 1}")) {
            assertThatThrownBy(() -> service.open(CLIENT_ID, command(body))).as(body)
                    .isInstanceOf(PlatformApiDataValidationException.class);
        }
        verifyNoInteractions(savings);
    }
}

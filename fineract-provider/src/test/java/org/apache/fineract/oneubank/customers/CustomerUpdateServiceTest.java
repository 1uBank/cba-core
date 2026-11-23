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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.dataqueries.service.DatatableWriteService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.address.service.AddressWritePlatformService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
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
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CustomerUpdateServiceTest {

    private static final long CLIENT_ID = 10L;

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private ClientRepositoryWrapper clientRepository;
    @Mock
    private ClientWritePlatformService clients;
    @Mock
    private ClientIdentifierWritePlatformService identifiers;
    @Mock
    private AddressWritePlatformService addresses;
    @Mock
    private DatatableWriteService datatables;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private AppUser user;

    private CustomerUpdateService service;

    @BeforeEach
    void setUp() {
        service = new CustomerUpdateService(context, clientRepository, clients, identifiers, addresses, datatables, jdbc);
        lenient().when(context.authenticatedUser()).thenReturn(user);
        Client client = mock(Client.class);
        lenient().when(client.officeId()).thenReturn(1L);
        lenient().when(clientRepository.findOneWithNotFoundDetection(CLIENT_ID)).thenReturn(client);
    }

    private static JsonCommand command(String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), new FromJsonHelper(), "ONEUBANKCUSTOMER", CLIENT_ID, null, null,
                CLIENT_ID, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void updatesTheClientFieldsSent() {
        when(clients.updateClient(eq(CLIENT_ID), any()))
                .thenReturn(new CommandProcessingResultBuilder().with(Map.of("mobileNo", "08011112222")).build());

        var result = service.update(CLIENT_ID, command("{\"client\": {\"mobileNo\": \"08011112222\"}}"));

        ArgumentCaptor<JsonCommand> sent = ArgumentCaptor.forClass(JsonCommand.class);
        verify(clients).updateClient(eq(CLIENT_ID), sent.capture());
        assertThat(sent.getValue().json()).isEqualTo("{\"mobileNo\":\"08011112222\"}");
        verify(user).validateHasUpdatePermission("CLIENT");
        assertThat(result.getClientId()).isEqualTo(CLIENT_ID);
        assertThat(result.getChanges()).containsKey("client");
        verifyNoInteractions(identifiers, addresses, datatables);
    }

    @Test
    void replacesAnIdentifierOfATypeTheCustomerHasAndAddsANewOne() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(CLIENT_ID), eq(1L))).thenReturn(List.of(55L));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(CLIENT_ID), eq(2L))).thenReturn(List.of());

        service.update(CLIENT_ID, command("""
                {"identifiers": [{"documentTypeId": 1, "documentKey": "22200000009"},
                                 {"documentTypeId": 2, "documentKey": "12345678909"}]}"""));

        verify(identifiers).updateClientIdentifier(eq(CLIENT_ID), eq(55L), any());
        verify(identifiers).addClientIdentifier(eq(CLIENT_ID), any());
        verify(user).validateHasUpdatePermission("CLIENTIDENTIFIER");
        verify(user).validateHasCreatePermission("CLIENTIDENTIFIER");
    }

    @Test
    void aRefusedPartStopsTheUpdateSoTheTransactionRollsBack() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(CLIENT_ID), eq(2L))).thenReturn(List.of());
        doThrow(new DuplicateClientIdentifierException("NIN")).when(identifiers).addClientIdentifier(eq(CLIENT_ID), any());

        assertThatThrownBy(() -> service.update(CLIENT_ID, command("""
                {"client": {"mobileNo": "08011112222"},
                 "identifiers": [{"documentTypeId": 2, "documentKey": "12345678909"}],
                 "address": {"addressTypeId": 7, "addressLine1": "5 Broad Street"}}""")))
                .isInstanceOf(DuplicateClientIdentifierException.class);

        InOrder order = inOrder(clients, identifiers);
        order.verify(clients).updateClient(eq(CLIENT_ID), any());
        order.verify(identifiers).addClientIdentifier(eq(CLIENT_ID), any());
        verifyNoInteractions(addresses);
    }

    @Test
    void replacesTheAddressOfThatTypeAndClearsFieldsNotSent() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(CLIENT_ID), eq(7L))).thenReturn(List.of(300L));

        service.update(CLIENT_ID, command("""
                {"address": {"addressTypeId": 7, "addressLine1": "5 Broad Street", "city": "Lagos Island",
                             "stateProvinceId": 25, "countryId": 160, "addressLine2": ""}}"""));

        ArgumentCaptor<JsonCommand> sent = ArgumentCaptor.forClass(JsonCommand.class);
        verify(addresses).updateClientAddress(eq(CLIENT_ID), sent.capture());
        assertThat(sent.getValue().longValueOfParameterNamed("addressId")).isEqualTo(300L);
        verify(jdbc).update("UPDATE m_address SET address_line_2 = NULL WHERE id = ?", 300L);
        verify(jdbc).update("UPDATE m_address SET postal_code = NULL WHERE id = ?", 300L);
        verify(jdbc, never()).update("UPDATE m_address SET city = NULL WHERE id = ?", 300L);
        verify(jdbc, never()).update("UPDATE m_address SET state_province_id = NULL WHERE id = ?", 300L);
        verify(addresses, never()).addClientAddress(any(), any(), any());
    }

    @Test
    void addsTheAddressWhenTheCustomerHasNoneOfThatType() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(CLIENT_ID), eq(7L))).thenReturn(List.of());

        service.update(CLIENT_ID, command("{\"address\": {\"addressTypeId\": 7, \"addressLine1\": \"5 Broad Street\"}}"));

        verify(addresses).addClientAddress(eq(CLIENT_ID), eq(7L), any());
        verify(user).validateHasCreatePermission("ADDRESS");
    }

    @Test
    void replacesAllRowsOfACustomerDatatable() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("Related Persons"))).thenReturn(1);

        var result = service.update(CLIENT_ID, command("""
                {"datatableRows": [{"registeredTableName": "Related Persons",
                                    "rows": [{"First Name": "Ada"}, {"First Name": "Bayo"}]}]}"""));

        InOrder order = inOrder(datatables);
        order.verify(datatables).deleteDatatableEntries(eq("Related Persons"), eq(CLIENT_ID), any());
        order.verify(datatables).createNewDatatableEntry("Related Persons", CLIENT_ID, "{\"First Name\":\"Ada\"}");
        order.verify(datatables).createNewDatatableEntry("Related Persons", CLIENT_ID, "{\"First Name\":\"Bayo\"}");
        verify(user).validateHasPermissionTo("DELETE_Related Persons");
        verify(user).validateHasPermissionTo("CREATE_Related Persons");
        assertThat(result.getChanges()).containsEntry("Related Persons", 2);
    }

    @Test
    void refusesATableThatIsNotACustomerDatatable() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("Loan Notes"))).thenReturn(0);

        assertThatThrownBy(() -> service.update(CLIENT_ID,
                command("{\"datatableRows\": [{\"registeredTableName\": \"Loan Notes\", \"rows\": []}]}")))
                .isInstanceOf(PlatformApiDataValidationException.class);
        verifyNoInteractions(datatables);
    }

    @Test
    void refusesAnEmptyOrMalformedRequestBeforeChangingAnything() {
        for (String body : List.of("{}", "{\"other\": 1}", "{\"client\": []}", "{\"identifiers\": [{\"documentKey\": \"x\"}]}",
                "{\"address\": {\"addressLine1\": \"x\"}}", "{\"datatableRows\": [{\"rows\": []}]}")) {
            assertThatThrownBy(() -> service.update(CLIENT_ID, command(body))).as(body)
                    .isInstanceOf(PlatformApiDataValidationException.class);
        }
        verifyNoInteractions(clients, identifiers, addresses, datatables);
    }
}

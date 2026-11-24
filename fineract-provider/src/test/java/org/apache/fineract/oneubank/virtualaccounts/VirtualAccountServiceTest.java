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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsApplicationProcessWritePlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class VirtualAccountServiceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private SavingsApplicationProcessWritePlatformService applications;
    @Mock
    private SavingsAccountWritePlatformService savings;
    @Mock
    private SavingsAccountRepository savingsAccountRepository;
    @Mock
    private AppUser user;

    private VirtualAccountService service;

    @BeforeEach
    void setUp() {
        service = new VirtualAccountService(context, jdbc, applications, savings, savingsAccountRepository);
        lenient().when(context.authenticatedUser()).thenReturn(user);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 11, 23))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    private static JsonCommand command(String json) {
        return JsonCommand.from(json, JsonParser.parseString(json), new FromJsonHelper(), "VIRTUALACCOUNT", null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private void parent(int status, boolean isVa) {
        Map<String, Object> row = new HashMap<>(Map.of("id", 158L, "client_id", 10L, "status_enum", status, "is_va", isVa));
        when(jdbc.queryForList(anyString(), eq("0020001580"))).thenReturn(List.of(row));
    }

    private void productFor(String type, Long... ids) {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(type))).thenReturn(List.of(ids));
    }

    private Map<String, Object> va(OffsetDateTime closedOn, BigDecimal balance) {
        Map<String, Object> row = new HashMap<>();
        row.put("savings_account_id", 200L);
        row.put("account_no", "6000002001");
        row.put("type", "SMART_TRANSFER");
        row.put("label", "Rent");
        row.put("parent_savings_account_id", 158L);
        row.put("parent_account_no", "0020001580");
        row.put("client_id", 10L);
        row.put("display_name", "Ada Obi");
        row.put("status_enum", closedOn == null ? 300 : 600);
        row.put("transaction_reference", "VA-1");
        row.put("created_on_utc", OffsetDateTime.of(2026, 11, 23, 9, 0, 0, 0, ZoneOffset.UTC));
        row.put("updated_on_utc", OffsetDateTime.of(2026, 11, 23, 9, 0, 0, 0, ZoneOffset.UTC));
        row.put("closed_on_utc", closedOn);
        return row;
    }

    @Test
    void opensTheAccountOnTheTypesProductUnderTheParentsCustomerThenRecordsIt() {
        parent(300, false);
        productFor("SMART_TRANSFER", 7L);
        when(applications.submitApplication(any())).thenReturn(new CommandProcessingResultBuilder().withSavingsId(200L).build());

        var result = service.create(command("""
                {"parentAccountNumber": "0020001580", "type": "SMART_TRANSFER", "label": "Rent", "transactionReference": "VA-1"}"""));

        ArgumentCaptor<JsonCommand> application = ArgumentCaptor.forClass(JsonCommand.class);
        InOrder order = inOrder(applications, savings, jdbc);
        order.verify(applications).submitApplication(application.capture());
        order.verify(applications).approveApplication(eq(200L), any());
        order.verify(savings).activate(eq(200L), any());
        order.verify(jdbc).update(anyString(), eq(200L), eq("SMART_TRANSFER"), eq(158L), eq("Rent"), eq("VA-1"), any(), any());
        assertThat(application.getValue().json()).contains("\"clientId\":10", "\"productId\":7", "\"submittedOnDate\":\"2026-11-23\"")
                .doesNotContain("accountNo");
        assertThat(result.getSavingsId()).isEqualTo(200L);
        assertThat(result.getClientId()).isEqualTo(10L);
    }

    @Test
    void anAirtimeAccountTakesThePhoneNumberAsItsNumber() {
        parent(300, false);
        productFor("AIRTIME", 8L);
        // Fineract's submit returns no ID when the number is supplied (the account is not flushed yet)
        when(applications.submitApplication(any())).thenReturn(new CommandProcessingResultBuilder().build());
        SavingsAccount account = org.mockito.Mockito.mock(SavingsAccount.class);
        when(account.getId()).thenReturn(201L);
        when(savingsAccountRepository.findSavingsAccountByAccountNumber("8031234567")).thenReturn(account);

        service.create(command("""
                {"parentAccountNumber": "0020001580", "type": "AIRTIME", "label": "Airtime", "accountNumber": "8031234567"}"""));

        verify(applications).approveApplication(eq(201L), any());

        ArgumentCaptor<JsonCommand> application = ArgumentCaptor.forClass(JsonCommand.class);
        verify(applications).submitApplication(application.capture());
        assertThat(application.getValue().json()).contains("\"accountNo\":\"8031234567\"");
    }

    @Test
    void refusesAPhoneNumberAlreadyInUse() {
        parent(300, false);
        productFor("AIRTIME", 8L);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("8031234567"))).thenReturn(true);

        assertThatThrownBy(() -> service.create(command("""
                {"parentAccountNumber": "0020001580", "type": "AIRTIME", "label": "Airtime", "accountNumber": "8031234567"}""")))
                .extracting("globalisationMessageCode").isEqualTo("error.msg.savingsaccount.duplicate.accountNo");
        verifyNoInteractions(applications, savings);
    }

    @Test
    void refusesATypeWithNoProduct() {
        parent(300, false);
        productFor("CABLE_TV");

        assertThatThrownBy(() -> service.create(command("""
                {"parentAccountNumber": "0020001580", "type": "CABLE_TV", "label": "DStv"}""")))
                .isInstanceOf(GeneralPlatformDomainRuleException.class).extracting("globalisationMessageCode")
                .isEqualTo("error.msg.oneubank.virtualaccount.type.unavailable");
        verifyNoInteractions(applications, savings);
    }

    @Test
    void refusesAParentThatIsNotActiveOrIsItselfVirtual() {
        parent(600, false);
        assertThatThrownBy(() -> service.create(command("""
                {"parentAccountNumber": "0020001580", "type": "SMART_TRANSFER", "label": "Rent"}""")))
                .extracting("globalisationMessageCode").isEqualTo("error.msg.oneubank.virtualaccount.parent.not.active");
    }

    @Test
    void refusesMalformedRequestsBeforeAnything() {
        for (String body : List.of("{\"parentAccountNumber\": \"0020001580\", \"type\": \"AIRTIME\", \"label\": \"A\"}",
                "{\"parentAccountNumber\": \"0020001580\", \"type\": \"SMART_TRANSFER\", \"label\": \"A\", \"accountNumber\": \"8031234567\"}",
                "{\"parentAccountNumber\": \"0020001580\", \"type\": \"GENERAL\", \"label\": \"A\"}",
                "{\"parentAccountNumber\": \"0020001580\", \"type\": \"SMART_TRANSFER\"}",
                "{\"type\": \"SMART_TRANSFER\", \"label\": \"A\"}", "{\"label\": \"A\", \"bvn\": \"22200000001\"}")) {
            assertThatThrownBy(() -> service.create(command(body))).as(body).isInstanceOf(PlatformApiDataValidationException.class);
        }
        verifyNoInteractions(applications, savings);
    }

    @Test
    void closesAnEmptyUnrestrictedAccount() {
        when(jdbc.queryForList(anyString(), eq(200L))).thenReturn(List.of(va(null, null)));
        when(jdbc.queryForMap(anyString(), eq(200L)))
                .thenReturn(Map.of("balance", BigDecimal.ZERO, "on_hold", BigDecimal.ZERO, "sub_status_enum", 0));

        service.close(200L, command("{}"));

        verify(savings).close(eq(200L), any());
        verify(jdbc).update(anyString(), any(), any(), eq(200L));
    }

    @Test
    void refusesToCloseWithMoneyOrRestrictions() {
        when(jdbc.queryForList(anyString(), eq(200L))).thenReturn(List.of(va(null, null)));
        when(jdbc.queryForMap(anyString(), eq(200L))).thenReturn(
                Map.of("balance", new BigDecimal("5.00"), "on_hold", BigDecimal.ZERO, "sub_status_enum", 0),
                Map.of("balance", BigDecimal.ZERO, "on_hold", new BigDecimal("1.00"), "sub_status_enum", 0),
                Map.of("balance", BigDecimal.ZERO, "on_hold", BigDecimal.ZERO, "sub_status_enum", 600));

        assertThatThrownBy(() -> service.close(200L, command("{}"))).extracting("globalisationMessageCode")
                .isEqualTo("error.msg.oneubank.virtualaccount.not.empty");
        assertThatThrownBy(() -> service.close(200L, command("{}"))).extracting("globalisationMessageCode")
                .isEqualTo("error.msg.oneubank.virtualaccount.restricted");
        assertThatThrownBy(() -> service.close(200L, command("{}"))).extracting("globalisationMessageCode")
                .isEqualTo("error.msg.oneubank.virtualaccount.restricted");
        verify(savings, never()).close(anyLong(), any());
    }

    @Test
    void closingAClosedAccountChangesNothing() {
        when(jdbc.queryForList(anyString(), eq(200L))).thenReturn(List.of(va(OffsetDateTime.now(ZoneOffset.UTC), null)));

        service.close(200L, command("{}"));

        verifyNoInteractions(savings);
    }

    @Test
    void renamesAnOpenAccountOnly() {
        when(jdbc.queryForList(anyString(), eq(200L))).thenReturn(List.of(va(null, null)),
                List.of(va(OffsetDateTime.now(ZoneOffset.UTC), null)));

        service.rename(200L, command("{\"label\": \"School fees\"}"));
        verify(jdbc).update(anyString(), eq("School fees"), any(), eq(200L));

        assertThatThrownBy(() -> service.rename(200L, command("{\"label\": \"X\"}"))).extracting("globalisationMessageCode")
                .isEqualTo("error.msg.oneubank.virtualaccount.closed");
    }

    @Test
    void namesTheAccountAfterItsOwnerAndLabel() {
        Map<String, Object> view = VirtualAccountService.view(va(null, null));

        assertThat(view).containsEntry("name", "Ada Obi - Rent").containsEntry("status", "ACTIVE")
                .containsEntry("parentAccountNumber", "0020001580").containsEntry("createdOn", "2026-11-23T09:00:00Z");
    }
}

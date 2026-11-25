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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class TransactionLookupServiceTest {

    private static Map<String, Object> row(int type, String amount, String after) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 17L);
        row.put("savings_account_id", 44L);
        row.put("account_no", "0770000190");
        row.put("client_id", 33L);
        row.put("transaction_type_enum", type);
        row.put("amount", new BigDecimal(amount));
        row.put("running_balance_derived", new BigDecimal(after));
        row.put("transaction_date", Date.valueOf("2026-11-24"));
        row.put("created_on_utc", OffsetDateTime.of(2026, 11, 24, 6, 46, 44, 0, ZoneOffset.UTC));
        row.put("is_reversed", false);
        row.put("is_reversal", false);
        return row;
    }

    @Test
    void aDebitHasItsBalanceBeforeAndAfter() {
        Map<String, Object> t = TransactionLookupService.transaction(row(2, "1500.500000", "8499.500000"));

        assertThat(t).containsEntry("entryType", "DEBIT").containsEntry("balanceAfter", "8499.500000")
                .containsEntry("balanceBefore", "10000.000000").containsEntry("transactionDate", "2026-11-24")
                .containsEntry("createdOn", "2026-11-24T06:46:44Z").doesNotContainKey("transfer");
    }

    @Test
    void aTransferSideCarriesBothSidesAndTheTransfersReference() {
        Map<String, Object> row = row(1, "200.000000", "200.000000");
        row.put("transfer_id", 3L);
        row.put("from_account_no", "0770000190");
        row.put("from_savings_transaction_id", 22L);
        row.put("from_name", "Trf Test1");
        row.put("to_account_no", "6000000037");
        row.put("to_savings_transaction_id", 23L);
        row.put("to_name", "Trf Test1");
        row.put("transfer_description", "Rent - 0770000190 to 6000000037");
        row.put("transfer_reference", "TOVA-1");

        Map<String, Object> t = TransactionLookupService.transaction(row);

        assertThat(t).containsEntry("entryType", "CREDIT").containsEntry("balanceBefore", "0.000000").containsEntry("transactionReference",
                "TOVA-1");
        assertThat((Map<String, Object>) t.get("transfer")).containsEntry("fromTransactionId", 22L).containsEntry("toAccountNo",
                "6000000037");
    }

    @Test
    void aDebitsReferenceIsItsExternalId() {
        Map<String, Object> row = row(2, "25.000000", "8464.500000");
        row.put("external_id", "DEB-1");
        row.put("payment_type", "SMS Fee");
        row.put("note", "SMS alerts");

        assertThat(TransactionLookupService.transaction(row)).containsEntry("transactionReference", "DEB-1")
                .containsEntry("paymentType", "SMS Fee").containsEntry("note", "SMS alerts");
    }
}

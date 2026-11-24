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
package org.apache.fineract.oneubank.savings;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.Test;

/** The 1UBank savings product fields, checked with Fineract's real validator. */
class SavingsProductOneUBankParamsTest {

    private static List<ApiParameterError> errors(String json) {
        List<ApiParameterError> errors = new ArrayList<>();
        SavingsProductOneUBankParams.validate(new DataValidatorBuilder(errors).resource("savingsproduct"), JsonParser.parseString(json),
                new FromJsonHelper());
        return errors;
    }

    @Test
    void acceptsEveryVirtualAccountTypeAndNone() {
        for (String type : SavingsProductOneUBankParams.VIRTUAL_ACCOUNT_TYPES) {
            assertThat(errors("{\"virtualAccountType\": \"" + type + "\"}")).as(type).isEmpty();
        }
        assertThat(errors("{\"virtualAccountType\": \"\"}")).isEmpty();
        assertThat(errors("{\"nubanPrefix\": \"600\", \"enforceTierLimits\": true}")).isEmpty();
    }

    @Test
    void refusesAnUnknownTypeOrPrefix() {
        assertThat(errors("{\"virtualAccountType\": \"GENERAL\"}")).extracting(ApiParameterError::getParameterName)
                .containsExactly("virtualAccountType");
        assertThat(errors("{\"virtualAccountType\": \"smart_transfer\"}")).hasSize(1);
        assertThat(errors("{\"nubanPrefix\": \"60\"}")).extracting(ApiParameterError::getParameterName).containsExactly("nubanPrefix");
    }
}

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

import com.google.gson.JsonElement;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;

/**
 * 1UBank fields on a savings product.
 *
 * <ul>
 * <li>{@code nubanPrefix}: the 3 digits that start the NUBAN of every account opened on the product. Optional: without
 * it the product's accounts keep Fineract's own numbering. Products may share a prefix (they then share its serials).
 * It cannot change once the product has accounts.</li>
 * <li>{@code enforceTierLimits}: whether the customer's KYC tier limits and the account's own transaction limit apply
 * to the product's accounts.</li>
 * </ul>
 */
public final class SavingsProductOneUBankParams {

    public static final String NUBAN_PREFIX = "nubanPrefix";
    public static final String ENFORCE_TIER_LIMITS = "enforceTierLimits";

    /** Raised by the database when a product that has accounts is given another prefix. */
    public static final String PREFIX_LOCKED_MARKER = "oneubank_nuban_prefix_locked";

    private static final Pattern PREFIX = Pattern.compile("^\\d{3}$");

    private SavingsProductOneUBankParams() {}

    public static void validate(DataValidatorBuilder validator, JsonElement element, FromJsonHelper json) {
        if (json.parameterExists(NUBAN_PREFIX, element)) {
            String prefix = json.extractStringNamed(NUBAN_PREFIX, element);
            if (StringUtils.isNotBlank(prefix)) {
                validator.reset().parameter(NUBAN_PREFIX).value(prefix).matchesRegularExpression(PREFIX.pattern(),
                        "must be exactly 3 digits");
            }
        }
        if (json.parameterExists(ENFORCE_TIER_LIMITS, element)) {
            validator.reset().parameter(ENFORCE_TIER_LIMITS).value(json.extractBooleanNamed(ENFORCE_TIER_LIMITS, element)).ignoreIfNull()
                    .validateForBooleanValue();
        }
    }

    /** A blank prefix means none. */
    public static String normalisePrefix(String prefix) {
        return StringUtils.isBlank(prefix) ? null : prefix.trim();
    }
}

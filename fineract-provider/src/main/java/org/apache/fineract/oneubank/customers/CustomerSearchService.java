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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Finds an owner's customers by BVN, NIN, email and/or mobile number, matching every field sent, in client ID order.
 * The owner is the API client recorded in the {@code Customer Owner} datatable. Identity numbers come in the request
 * body, so they never appear in URLs or access logs.
 */
@Service
@RequiredArgsConstructor
public class CustomerSearchService {

    static final int MAX_LIMIT = 100;
    private static final Set<String> SUPPORTED = Set.of("owner", "bvn", "nin", "email", "mobileNumber", "afterClientId", "limit");

    private final PlatformSecurityContext context;
    private final NamedParameterJdbcTemplate jdbc;
    private final FromJsonHelper fromJsonHelper;

    /** @return {@code clientIds}, at most {@code limit}, all above {@code afterClientId} */
    public Map<String, Object> search(String json) {
        context.authenticatedUser().validateHasReadPermission("CLIENT");
        JsonElement parsed = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json);
        if (!parsed.isJsonObject()) {
            throw invalid("body", "The request must be a JSON object");
        }
        JsonObject body = parsed.getAsJsonObject();
        fromJsonHelper.checkForUnsupportedParameters(body, SUPPORTED);
        String owner = fromJsonHelper.extractStringNamed("owner", body);
        String bvn = fromJsonHelper.extractStringNamed("bvn", body);
        String nin = fromJsonHelper.extractStringNamed("nin", body);
        String email = fromJsonHelper.extractStringNamed("email", body);
        String mobile = fromJsonHelper.extractStringNamed("mobileNumber", body);
        Long after = fromJsonHelper.extractLongNamed("afterClientId", body);
        Integer limit = fromJsonHelper.extractIntegerSansLocaleNamed("limit", body);
        if (owner == null || owner.isBlank()) {
            throw invalid("owner", "The parameter `owner` is mandatory");
        }
        if (bvn == null && nin == null && email == null && mobile == null) {
            throw invalid("bvn", "Send at least one of `bvn`, `nin`, `email`, `mobileNumber`");
        }
        int pageSize = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_LIMIT));

        StringBuilder sql = new StringBuilder("""
                SELECT c.id FROM m_client c
                JOIN "Customer Owner" o ON o.client_id = c.id AND o."Owner" = :owner
                WHERE c.id > :after""");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("owner", owner).addValue("after", after == null ? 0L : after)
                .addValue("limit", pageSize);
        identifier(sql, params, "bvn", "BVN", bvn);
        identifier(sql, params, "nin", "NIN", nin);
        if (email != null) {
            sql.append(" AND lower(c.email_address) = lower(:email)");
            params.addValue("email", email);
        }
        if (mobile != null) {
            sql.append(" AND c.mobile_no = :mobile");
            params.addValue("mobile", mobile);
        }
        sql.append(" ORDER BY c.id LIMIT :limit");

        List<Long> ids = jdbc.queryForList(sql.toString(), params, Long.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientIds", ids);
        result.put("hasMore", ids.size() == pageSize && hasMore(sql.toString(), params, ids.get(ids.size() - 1)));
        return result;
    }

    private boolean hasMore(String sql, MapSqlParameterSource params, Long last) {
        MapSqlParameterSource next = new MapSqlParameterSource(params.getValues()).addValue("after", last).addValue("limit", 1);
        return !jdbc.queryForList(sql, next, Long.class).isEmpty();
    }

    private static void identifier(StringBuilder sql, MapSqlParameterSource params, String name, String type, String value) {
        if (value == null) {
            return;
        }
        sql.append(" AND EXISTS (SELECT 1 FROM m_client_identifier ci JOIN m_code_value cv ON cv.id = ci.document_type_id"
                + " WHERE ci.client_id = c.id AND cv.code_value = :" + name + "Type AND ci.document_key = :" + name + ")");
        params.addValue(name + "Type", type).addValue(name, value);
    }

    private static PlatformApiDataValidationException invalid(String parameter, String message) {
        List<ApiParameterError> errors = new ArrayList<>();
        errors.add(ApiParameterError.parameterError("validation.msg.oneubank.customer.search.invalid", message, parameter));
        return new PlatformApiDataValidationException(errors);
    }
}

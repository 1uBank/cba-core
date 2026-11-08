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
package org.apache.fineract.oneubank.authsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls the auth service's Admin REST API to create, update and disable staff users.
 *
 * <p>
 * Uses the JDK HTTP client rather than Keycloak's admin client library, so this module adds no dependencies that could
 * clash with Fineract's own.
 */
public class AuthServiceAdminClient {

    private final AuthSyncProperties properties;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    public AuthServiceAdminClient(AuthSyncProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build());
    }

    AuthServiceAdminClient(AuthSyncProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    /** Returns the auth service's ID for the user, if a user with exactly this username exists. */
    public Optional<String> findUserId(String username) {
        String path = "/users?exact=true&username=" + encode(username);
        JsonNode users = readJson(send(request(path).GET(), 200));
        if (users.isArray() && !users.isEmpty()) {
            return Optional.of(users.get(0).get("id").asText());
        }
        return Optional.empty();
    }

    /** Creates the user and returns its ID. New users must complete the configured required actions at first login. */
    public String createUser(StaffAccount account) {
        Map<String, Object> body = representation(account);
        body.put("enabled", account.active());
        body.put("emailVerified", false);
        body.put("requiredActions", properties.getRequiredActions());
        send(request("/users").POST(jsonBody(body)), 201);
        return findUserId(account.username()).orElseThrow(() -> new AuthSyncException(
                "User " + account.username() + " was created in the auth service but could not be found afterwards"));
    }

    /** Updates the user's username, name, email and enabled flag. */
    public void updateUser(String userId, StaffAccount account) {
        Map<String, Object> body = representation(account);
        body.put("enabled", account.active());
        send(request("/users/" + userId).PUT(jsonBody(body)), 204);
    }

    /** Ends all of the user's sessions, so a disabled user is signed out at once. */
    public void signOut(String userId) {
        send(request("/users/" + userId + "/logout").POST(HttpRequest.BodyPublishers.noBody()), 204);
    }

    /**
     * Emails the user a link to complete the given actions, for example setting a password and an authenticator app.
     */
    public void sendActionsEmail(String userId, List<String> actions) {
        send(request("/users/" + userId + "/execute-actions-email").PUT(jsonBody(actions)), 204);
    }

    private Map<String, Object> representation(StaffAccount account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", account.username());
        body.put("email", account.email());
        body.put("firstName", account.firstName());
        body.put("lastName", account.lastName());
        return body;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(adminUrl() + path)).timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken()).header("Content-Type", "application/json");
    }

    private String send(HttpRequest.Builder builder, int expectedStatus) {
        HttpRequest httpRequest = builder.build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expectedStatus) {
                throw new AuthSyncException("Auth service returned " + response.statusCode() + " for " + httpRequest.method() + " "
                        + httpRequest.uri().getPath());
            }
            return response.body();
        } catch (IOException e) {
            throw new AuthSyncException("Could not reach the auth service at " + httpRequest.uri().getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthSyncException("Interrupted while calling the auth service", e);
        }
    }

    private synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(properties.getClientId()) + "&client_secret="
                + encode(properties.getClientSecret());
        HttpRequest tokenRequest = HttpRequest
                .newBuilder(URI.create(baseUrl() + "/realms/" + properties.getRealm() + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds())).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> response = http.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AuthSyncException(
                        "Auth service refused a token for client " + properties.getClientId() + " (status " + response.statusCode() + ")");
            }
            JsonNode token = readJson(response.body());
            accessToken = token.get("access_token").asText();
            // Renew 30 seconds before expiry.
            accessTokenExpiry = Instant.now().plusSeconds(Math.max(0, token.get("expires_in").asLong() - 30));
            return accessToken;
        } catch (IOException e) {
            throw new AuthSyncException("Could not reach the auth service at " + tokenRequest.uri().getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthSyncException("Interrupted while getting a token from the auth service", e);
        }
    }

    private String adminUrl() {
        return baseUrl() + "/admin/realms/" + properties.getRealm();
    }

    private String baseUrl() {
        String base = properties.getBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private HttpRequest.BodyPublisher jsonBody(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        } catch (IOException e) {
            throw new AuthSyncException("Could not write request body", e);
        }
    }

    private JsonNode readJson(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new AuthSyncException("Auth service returned a response that is not JSON", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}

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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Lets the auth service move an existing staff member across at their first login, keeping their current password.
 *
 * <p>
 * Called only by the auth service's migration plugin, signed in as {@link AuthSyncProperties#getMigratorUsername()}.
 * Passwords are checked here against Fineract's stored hash and never stored or logged.
 */
@Path("/v1/oneubank/staff-migration")
public class StaffMigrationApiResource {

    private final PlatformSecurityContext context;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthSyncProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public StaffMigrationApiResource(PlatformSecurityContext context, AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
            AuthSyncProperties properties) {
        this.context = context;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** Returns the staff member's name and email if they can be migrated; 404 otherwise. */
    @GET
    @Path("{username}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response find(@PathParam("username") String username) {
        requireMigrator();
        return migratable(username).map(user -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", user.getUsername());
            body.put("email", user.getEmail());
            body.put("firstName", user.getFirstname());
            body.put("lastName", user.getLastname());
            return Response.ok(write(body)).build();
        }).orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /** Checks a password against Fineract's stored hash. Body: {@code {"password": "..."}}. */
    @POST
    @Path("{username}/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verify(@PathParam("username") String username, String requestBody) {
        requireMigrator();
        String password = readPassword(requestBody);
        boolean valid = password != null
                && migratable(username).map(user -> passwordEncoder.matches(password, user.getPassword())).orElse(false);
        return Response.ok(write(Map.of("valid", valid))).build();
    }

    private Optional<AppUser> migratable(String username) {
        if (username == null || username.startsWith(properties.getServiceAccountPrefix())) {
            return Optional.empty();
        }
        AppUser user = appUserRepository.findAppUserByName(username);
        if (user == null || user.isDeleted() || !user.isEnabled() || user.isSystemUser()) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    private void requireMigrator() {
        AppUser caller = context.authenticatedUser();
        if (!properties.getMigratorUsername().equals(caller.getUsername())) {
            throw new NoAuthorizationException("Only the auth service's migration client may call this endpoint");
        }
    }

    private String readPassword(String requestBody) {
        try {
            JsonNode body = json.readTree(requestBody == null ? "{}" : requestBody);
            JsonNode password = body.get("password");
            return password == null || password.isNull() ? null : password.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write response", e);
        }
    }
}

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

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;

/**
 * Lets admins manage a staff member's sign-in from the portal: see whether they have set up an authenticator app or are
 * locked out, reset the authenticator after a lost phone, and lift a lock-out.
 *
 * <p>
 * The portal calls CBA-CORE, which checks the admin's permissions and then calls the auth service. Reading needs
 * {@code READ_USER}; changes need {@code UPDATE_USER}.
 */
@Slf4j
@Path("/v1/oneubank/staff/{userId}/sign-in")
public class StaffSignInApiResource {

    private final PlatformSecurityContext context;
    private final AppUserRepository appUserRepository;
    private final AuthServiceAdminClient authService;
    private final ObjectMapper json = new ObjectMapper();

    public StaffSignInApiResource(PlatformSecurityContext context, AppUserRepository appUserRepository,
            AuthServiceAdminClient authService) {
        this.context = context;
        this.appUserRepository = appUserRepository;
        this.authService = authService;
    }

    /** {@code inAuthService}, {@code authenticatorSetUp} and {@code lockedOut} for the staff member. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("userId") Long userId) {
        context.authenticatedUser().validateHasReadPermission("USER");
        return withAuthServiceUser(userId, authUserId -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inAuthService", authUserId != null);
            body.put("authenticatorSetUp", authUserId != null && authService.hasAuthenticator(authUserId));
            body.put("lockedOut", authUserId != null && authService.isLockedOut(authUserId));
            return Response.ok(write(body)).build();
        });
    }

    /** Removes the authenticator app; the staff member sets up a new one at their next sign-in. */
    @POST
    @Path("reset-authenticator")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetAuthenticator(@PathParam("userId") Long userId) {
        AppUser admin = context.authenticatedUser();
        admin.validateHasPermissionTo("UPDATE_USER");
        return withAuthServiceUser(userId, authUserId -> {
            if (authUserId == null) {
                return notInAuthService();
            }
            authService.resetAuthenticator(authUserId);
            authService.signOut(authUserId);
            log.info("Authenticator of staff user {} reset by {}", userId, admin.getUsername());
            return Response.ok(write(Map.of("resourceId", userId))).build();
        });
    }

    /** Lifts the lock set after too many failed sign-in attempts. */
    @POST
    @Path("unlock")
    @Produces(MediaType.APPLICATION_JSON)
    public Response unlock(@PathParam("userId") Long userId) {
        AppUser admin = context.authenticatedUser();
        admin.validateHasPermissionTo("UPDATE_USER");
        return withAuthServiceUser(userId, authUserId -> {
            if (authUserId == null) {
                return notInAuthService();
            }
            authService.unlock(authUserId);
            log.info("Sign-in of staff user {} unlocked by {}", userId, admin.getUsername());
            return Response.ok(write(Map.of("resourceId", userId))).build();
        });
    }

    /** Runs the action with the staff member's auth-service ID, or null if they are not there yet. */
    private Response withAuthServiceUser(Long userId, Function<String, Response> action) {
        Optional<AppUser> user = appUserRepository.findById(userId);
        if (user.isEmpty() || user.get().isDeleted()) {
            return error(Response.Status.NOT_FOUND, "No staff user with ID " + userId);
        }
        try {
            return action.apply(authService.findUserId(user.get().getUsername()).orElse(null));
        } catch (AuthSyncException e) {
            log.error("Sign-in settings of staff user {} could not be read or changed in the auth service: {}", userId, e.getMessage());
            return error(Response.Status.BAD_GATEWAY, "The auth service could not be reached. Try again shortly.");
        }
    }

    private Response notInAuthService() {
        return error(Response.Status.CONFLICT, "This staff member has not signed in through the auth service yet.");
    }

    private Response error(Response.Status status, String message) {
        return Response.status(status).entity(write(Map.of("defaultUserMessage", message, "httpStatusCode", status.getStatusCode())))
                .type(MediaType.APPLICATION_JSON).build();
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write response", e);
        }
    }
}

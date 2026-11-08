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

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies a change to a Fineract staff user to the same user in the auth service.
 *
 * <p>
 * Every method is safe to repeat: it looks the user up by username first, so a retried call updates instead of creating
 * a second user. Failures are logged, not thrown: the Fineract change has already been saved, and the staff member's
 * permissions are still governed by Fineract.
 */
@Slf4j
public class StaffUserSync {

    private final AuthServiceAdminClient authService;
    private final AuthSyncProperties properties;

    public StaffUserSync(AuthServiceAdminClient authService, AuthSyncProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    /** A staff user was created in Fineract. */
    public void created(StaffAccount account) {
        if (isServiceAccount(account.username())) {
            return;
        }
        try {
            Optional<String> existing = authService.findUserId(account.username());
            String userId;
            if (existing.isPresent()) {
                userId = existing.get();
                authService.updateUser(userId, account);
                log.info("Staff user {} already existed in the auth service and was updated", account.username());
            } else {
                userId = authService.createUser(account);
                log.info("Staff user {} created in the auth service", account.username());
            }
            if (properties.isSendSetupEmail()) {
                authService.sendActionsEmail(userId, properties.getRequiredActions());
            }
        } catch (AuthSyncException e) {
            log.error("Staff user {} was created in Fineract but not in the auth service: {}", account.username(), e.getMessage());
        }
    }

    /** A staff user was updated in Fineract. The username may have changed, so the previous one is passed too. */
    public void updated(String previousUsername, StaffAccount account) {
        if (isServiceAccount(previousUsername) || isServiceAccount(account.username())) {
            return;
        }
        try {
            Optional<String> userId = authService.findUserId(previousUsername).or(() -> authService.findUserId(account.username()));
            if (userId.isEmpty()) {
                created(account);
                return;
            }
            authService.updateUser(userId.get(), account);
            if (!account.active()) {
                authService.signOut(userId.get());
            }
            log.info("Staff user {} updated in the auth service", account.username());
        } catch (AuthSyncException e) {
            log.error("Staff user {} was updated in Fineract but not in the auth service: {}", account.username(), e.getMessage());
        }
    }

    /**
     * A staff user was deleted in Fineract. The auth-service user is disabled and signed out, not deleted, to keep the
     * audit trail.
     */
    public void deleted(StaffAccount accountBeforeDeletion) {
        if (isServiceAccount(accountBeforeDeletion.username())) {
            return;
        }
        try {
            Optional<String> userId = authService.findUserId(accountBeforeDeletion.username());
            if (userId.isEmpty()) {
                return;
            }
            StaffAccount disabled = new StaffAccount(accountBeforeDeletion.username(), accountBeforeDeletion.email(),
                    accountBeforeDeletion.firstName(), accountBeforeDeletion.lastName(), false);
            authService.updateUser(userId.get(), disabled);
            authService.signOut(userId.get());
            log.info("Staff user {} disabled in the auth service", accountBeforeDeletion.username());
        } catch (AuthSyncException e) {
            log.error("Staff user {} was deleted in Fineract but is still enabled in the auth service: {}",
                    accountBeforeDeletion.username(), e.getMessage());
        }
    }

    private boolean isServiceAccount(String username) {
        return username != null && username.startsWith(properties.getServiceAccountPrefix());
    }
}

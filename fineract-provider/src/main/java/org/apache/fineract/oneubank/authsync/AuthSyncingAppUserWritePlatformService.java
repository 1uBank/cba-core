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
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.service.AppUserWritePlatformService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wraps Fineract's user service. Fineract does all the work as before; once its change is committed, the same change is
 * applied to the staff user in the auth service.
 *
 * <p>
 * The auth service is called only after the commit, so a Fineract change that rolls back never reaches it.
 */
public class AuthSyncingAppUserWritePlatformService implements AppUserWritePlatformService {

    private final AppUserWritePlatformService delegate;
    private final AppUserRepository appUserRepository;
    private final StaffUserSync sync;

    public AuthSyncingAppUserWritePlatformService(AppUserWritePlatformService delegate, AppUserRepository appUserRepository,
            StaffUserSync sync) {
        this.delegate = delegate;
        this.appUserRepository = appUserRepository;
        this.sync = sync;
    }

    @Override
    public CommandProcessingResult createUser(JsonCommand command) {
        CommandProcessingResult result = delegate.createUser(command);
        load(result.getResourceId()).ifPresent(created -> afterCommit(() -> sync.created(created)));
        return result;
    }

    @Override
    public CommandProcessingResult updateUser(Long userId, JsonCommand command) {
        Optional<StaffAccount> before = load(userId);
        CommandProcessingResult result = delegate.updateUser(userId, command);
        load(userId)
                .ifPresent(after -> afterCommit(() -> sync.updated(before.map(StaffAccount::username).orElse(after.username()), after)));
        return result;
    }

    @Override
    public CommandProcessingResult deleteUser(Long userId) {
        // Fineract renames a user when deleting it, so the account is read before the delete.
        Optional<StaffAccount> before = load(userId);
        CommandProcessingResult result = delegate.deleteUser(userId);
        before.ifPresent(account -> afterCommit(() -> sync.deleted(account)));
        return result;
    }

    @Override
    public CommandProcessingResult changeUserPassword(Long userId, JsonCommand command) {
        // Staff passwords live in the auth service. Fineract's own password is kept as it is.
        return delegate.changeUserPassword(userId, command);
    }

    private Optional<StaffAccount> load(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return appUserRepository.findById(userId).map(StaffAccount::from);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // No transaction is open, so the change is already saved.
            action.run();
        }
    }
}

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaffUserSyncTest {

    private static final StaffAccount ADA = new StaffAccount("ada", "ada@example.com", "Ada", "Obi", true);

    @Mock
    private AuthServiceAdminClient authService;

    private StaffUserSync sync;

    @BeforeEach
    void setUp() {
        sync = new StaffUserSync(authService, new AuthSyncProperties());
    }

    @Test
    void createdUserIsCreatedInTheAuthService() {
        when(authService.findUserId("ada")).thenReturn(Optional.empty());
        when(authService.createUser(ADA)).thenReturn("kc-1");

        sync.created(ADA);

        verify(authService).createUser(ADA);
    }

    @Test
    void createdUserThatAlreadyExistsIsUpdatedNotDuplicated() {
        when(authService.findUserId("ada")).thenReturn(Optional.of("kc-1"));

        sync.created(ADA);

        verify(authService).updateUser("kc-1", ADA);
        verify(authService, never()).createUser(any());
    }

    @Test
    void serviceAccountsAreSkipped() {
        sync.created(new StaffAccount("service-account-banking-api", null, null, null, true));

        verifyNoInteractions(authService);
    }

    @Test
    void renamedUserIsFoundByItsPreviousUsername() {
        StaffAccount renamed = new StaffAccount("ada.obi", "ada@example.com", "Ada", "Obi", true);
        when(authService.findUserId("ada")).thenReturn(Optional.of("kc-1"));

        sync.updated("ada", renamed);

        verify(authService).updateUser("kc-1", renamed);
    }

    @Test
    void disabledUserIsSignedOut() {
        StaffAccount disabled = new StaffAccount("ada", "ada@example.com", "Ada", "Obi", false);
        when(authService.findUserId("ada")).thenReturn(Optional.of("kc-1"));

        sync.updated("ada", disabled);

        verify(authService).updateUser("kc-1", disabled);
        verify(authService).signOut("kc-1");
    }

    @Test
    void deletedUserIsDisabledAndSignedOut() {
        when(authService.findUserId("ada")).thenReturn(Optional.of("kc-1"));

        sync.deleted(ADA);

        verify(authService).updateUser("kc-1", new StaffAccount("ada", "ada@example.com", "Ada", "Obi", false));
        verify(authService).signOut("kc-1");
    }

    @Test
    void authServiceFailureDoesNotPropagate() {
        when(authService.findUserId(anyString())).thenThrow(new AuthSyncException("down"));

        sync.created(ADA);
        sync.updated("ada", ADA);
        sync.deleted(ADA);
    }
}

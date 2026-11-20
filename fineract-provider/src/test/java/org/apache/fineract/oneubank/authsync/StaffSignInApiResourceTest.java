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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaffSignInApiResourceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private AuthServiceAdminClient authService;
    @Mock
    private AppUser admin;
    @Mock
    private AppUser ada;

    private StaffSignInApiResource resource;

    @BeforeEach
    void setUp() {
        resource = new StaffSignInApiResource(context, appUserRepository, authService);
        when(context.authenticatedUser()).thenReturn(admin);
        when(admin.getUsername()).thenReturn("admin.user");
        when(ada.getUsername()).thenReturn("ada");
        when(appUserRepository.findById(7L)).thenReturn(Optional.of(ada));
    }

    @Test
    void statusReportsAuthenticatorAndLockOut() {
        when(authService.findUserId("ada")).thenReturn(Optional.of("kc-1"));
        when(authService.hasAuthenticator("kc-1")).thenReturn(true);
        when(authService.isLockedOut("kc-1")).thenReturn(false);

        var response = resource.status(7L);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((String) response.getEntity()).contains("\"authenticatorSetUp\":true").contains("\"lockedOut\":false");
    }

    @Test
    void resetAuthenticatorRemovesItAndSignsTheStaffMemberOut() {
        when(authService.findUserId("ada")).thenReturn(Optional.of("kc-1"));

        var response = resource.resetAuthenticator(7L);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(authService).resetAuthenticator("kc-1");
        verify(authService).signOut("kc-1");
    }

    @Test
    void unlockLiftsTheLockOut() {
        when(authService.findUserId("ada")).thenReturn(Optional.of("kc-1"));

        assertThat(resource.unlock(7L).getStatus()).isEqualTo(200);
        verify(authService).unlock("kc-1");
    }

    @Test
    void changesNeedUpdateUserPermission() {
        doThrow(new NoAuthorizationException("no")).when(admin).validateHasPermissionTo("UPDATE_USER");

        org.junit.jupiter.api.Assertions.assertThrows(NoAuthorizationException.class, () -> resource.resetAuthenticator(7L));
        verify(authService, never()).resetAuthenticator(anyString());
    }

    @Test
    void staffNotYetInTheAuthServiceGetConflict() {
        when(authService.findUserId("ada")).thenReturn(Optional.empty());

        assertThat(resource.resetAuthenticator(7L).getStatus()).isEqualTo(409);
    }

    @Test
    void unknownStaffGetNotFound() {
        when(appUserRepository.findById(8L)).thenReturn(Optional.empty());

        assertThat(resource.status(8L).getStatus()).isEqualTo(404);
    }

    @Test
    void authServiceDownGivesBadGateway() {
        when(authService.findUserId("ada")).thenThrow(new AuthSyncException("down"));

        assertThat(resource.unlock(7L).getStatus()).isEqualTo(502);
    }
}

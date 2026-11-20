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

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for keeping staff users in the auth service in step with Fineract users.
 *
 * <p>
 * Bound from {@code oneubank.auth-sync.*}, or from environment variables such as {@code ONEUBANK_AUTHSYNC_ENABLED} and
 * {@code ONEUBANK_AUTHSYNC_CLIENTSECRET}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "oneubank.auth-sync")
public class AuthSyncProperties {

    /** Turns the sync on. Off by default, so stock Fineract behaviour is unchanged. */
    private boolean enabled = false;

    /** Base URL of the auth service, for example {@code https://auth.example.com}. */
    private String baseUrl;

    /** Realm that holds staff users. */
    private String realm = "cba";

    /** Client with permission to manage users in the realm. */
    private String clientId = "user-provisioner";

    /** Secret of {@link #clientId}. Comes from Secrets Manager, never from a file in git. */
    private String clientSecret;

    /** Steps a new staff member must complete at first login. */
    private List<String> requiredActions = List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP");

    /** Email a new staff member a link to complete {@link #requiredActions}. Needs SMTP configured in the realm. */
    private boolean sendSetupEmail = false;

    /**
     * Usernames with this prefix are service accounts, which are auth-service clients, not users, so they are skipped.
     */
    private String serviceAccountPrefix = "service-account-";

    /**
     * Fineract user the auth service's first-login migration signs in as. Only this user may call the staff-migration
     * endpoints.
     */
    private String migratorUsername = "service-account-user-migrator";

    /**
     * Name of the auth service's first-login migration in the realm. New staff, and staff whose password an admin
     * resets, are linked to it, so their next sign-in checks the password Fineract holds before asking them to choose
     * their own.
     */
    private String migrationComponentName = "cba-core-user-migration";

    /** Timeout for each call to the auth service, in seconds. */
    private int timeoutSeconds = 10;
}

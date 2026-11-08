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

import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.service.AppUserWritePlatformService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Keeps staff users in the auth service in step with Fineract users. Off unless
 * {@code oneubank.auth-sync.enabled=true}.
 *
 * <p>
 * Fineract stays where staff are created, edited and deleted. This wraps Fineract's user service instead of replacing
 * it, so no existing Fineract file is changed.
 */
@Configuration
@ConditionalOnProperty(prefix = "oneubank.auth-sync", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AuthSyncProperties.class)
public class AuthSyncConfiguration {

    @Bean
    public AuthServiceAdminClient authServiceAdminClient(AuthSyncProperties properties) {
        if (isBlank(properties.getBaseUrl()) || isBlank(properties.getClientId()) || isBlank(properties.getClientSecret())) {
            throw new IllegalStateException(
                    "oneubank.auth-sync is enabled but base-url, client-id or client-secret is missing (ONEUBANK_AUTHSYNC_BASEURL, ONEUBANK_AUTHSYNC_CLIENTID, ONEUBANK_AUTHSYNC_CLIENTSECRET)");
        }
        return new AuthServiceAdminClient(properties);
    }

    @Bean
    public StaffUserSync staffUserSync(AuthServiceAdminClient authServiceAdminClient, AuthSyncProperties properties) {
        return new StaffUserSync(authServiceAdminClient, properties);
    }

    @Bean
    public StaffMigrationApiResource staffMigrationApiResource(PlatformSecurityContext context, AppUserRepository appUserRepository,
            ObjectProvider<PasswordEncoder> passwordEncoder, AuthSyncProperties properties) {
        // Same encoder Fineract stores passwords with; the fallback reads the same {id}hash format.
        return new StaffMigrationApiResource(context, appUserRepository,
                passwordEncoder.getIfAvailable(PasswordEncoderFactories::createDelegatingPasswordEncoder), properties);
    }

    /**
     * Wraps Fineract's user service once Spring has built it, including its transaction proxy. Dependencies are looked
     * up lazily so the post-processor does not force other beans to be created early.
     */
    @Bean
    public static BeanPostProcessor authSyncUserServiceWrapper(ObjectProvider<AppUserRepository> appUserRepository,
            ObjectProvider<StaffUserSync> staffUserSync) {
        return new WrappingPostProcessor(appUserRepository, staffUserSync);
    }

    static final class WrappingPostProcessor implements BeanPostProcessor, Ordered {

        private final ObjectProvider<AppUserRepository> appUserRepository;
        private final ObjectProvider<StaffUserSync> staffUserSync;

        WrappingPostProcessor(ObjectProvider<AppUserRepository> appUserRepository, ObjectProvider<StaffUserSync> staffUserSync) {
            this.appUserRepository = appUserRepository;
            this.staffUserSync = staffUserSync;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof AppUserWritePlatformService service && !(bean instanceof AuthSyncingAppUserWritePlatformService)) {
                return new AuthSyncingAppUserWritePlatformService(service, appUserRepository.getObject(), staffUserSync.getObject());
            }
            return bean;
        }

        @Override
        public int getOrder() {
            // Run after Spring's own post-processors, so the transaction proxy is already in place.
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

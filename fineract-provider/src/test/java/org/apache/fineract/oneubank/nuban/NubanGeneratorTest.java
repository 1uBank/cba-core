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
package org.apache.fineract.oneubank.nuban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NubanGeneratorTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NubanGenerator generator = new NubanGenerator(jdbc);

    @Test
    void checkDigitMatchesNubansIssuedByTransactionPoint() {
        assertThat(NubanGenerator.nuban("111", 1)).isEqualTo("1110000018");
        assertThat(NubanGenerator.nuban("111", 3)).isEqualTo("1110000032");
    }

    @Test
    void productsWithoutPrefixKeepFineractNumbering() {
        assertThat(generator.next(null)).isNull();
        verifyNoInteractions(jdbc);
    }

    @Test
    void takesTheNextSerialOfThePrefix() {
        sequenceExists("002", true);
        when(jdbc.queryForObject("SELECT nextval('nuban_serial_002')", Long.class)).thenReturn(42L);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(NubanGenerator.nuban("002", 42)))).thenReturn(false);

        assertThat(generator.next("002")).isEqualTo(NubanGenerator.nuban("002", 42)).startsWith("002000042").hasSize(10);
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void createsTheCounterOnFirstUseOfAPrefix() {
        sequenceExists("600", false);
        when(jdbc.queryForObject("SELECT nextval('nuban_serial_600')", Long.class)).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(NubanGenerator.nuban("600", 1)))).thenReturn(false);

        assertThat(generator.next("600")).isEqualTo(NubanGenerator.nuban("600", 1));
        verify(jdbc).execute("CREATE SEQUENCE IF NOT EXISTS nuban_serial_600 MINVALUE 1 MAXVALUE 999999 NO CYCLE");
    }

    @Test
    void skipsNumbersAlreadyHeldByAnAccount() {
        sequenceExists("000", true);
        when(jdbc.queryForObject("SELECT nextval('nuban_serial_000')", Long.class)).thenReturn(1441L, 1442L);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(NubanGenerator.nuban("000", 1441)))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(NubanGenerator.nuban("000", 1442)))).thenReturn(false);

        assertThat(generator.next("000")).isEqualTo(NubanGenerator.nuban("000", 1442));
    }

    @Test
    void refusesAMalformedPrefix() {
        assertThatThrownBy(() -> generator.next("12a")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }

    private void sequenceExists(String prefix, boolean exists) {
        when(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "nuban_serial_" + prefix)).thenReturn(exists);
    }
}

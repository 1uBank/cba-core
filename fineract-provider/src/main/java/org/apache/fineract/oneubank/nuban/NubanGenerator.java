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

import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Account numbers in the CBN NUBAN format, as TransactionPoint issues them: the product's 3-digit prefix, a 6-digit
 * serial and a check digit over 1UBank's CBN institution code.
 *
 * <p>
 * Each prefix has its own serial counter, a PostgreSQL sequence named {@code nuban_serial_<prefix>}, shared by every
 * product with that prefix. After a migration the sequences are moved past the serials already issued (see
 * {@code scripts/oneubank/nuban-serials-after-migration.sql}). A number already held by an account is skipped, so a
 * sequence that was not moved on can never hand out a duplicate.
 */
@Component
@RequiredArgsConstructor
public class NubanGenerator {

    /** 1UBank's CBN institution code. */
    public static final String INSTITUTION_CODE = "090605";

    static final int MAX_SERIAL = 999_999;
    private static final int MAX_SKIPS = 1_000;
    private static final Pattern PREFIX = Pattern.compile("^\\d{3}$");
    private static final int[] WEIGHTS = { 3, 7, 3 };

    private final JdbcTemplate jdbcTemplate;

    /**
     * The next free NUBAN for the prefix, or {@code null} when there is no prefix (the account keeps Fineract's own
     * numbering).
     */
    public String next(String prefix) {
        if (prefix == null) {
            return null;
        }
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalStateException("A NUBAN prefix must be exactly 3 digits");
        }
        String sequence = sequenceName(prefix);
        createSequenceIfMissing(sequence);
        for (int skipped = 0; skipped <= MAX_SKIPS; skipped++) {
            String nuban = nuban(prefix, nextSerial(prefix, sequence));
            if (!taken(nuban)) {
                return nuban;
            }
        }
        throw new PlatformDataIntegrityException("error.msg.nuban.serials.taken",
                "Too many NUBANs with prefix " + prefix + " are already in use; move its serial past them");
    }

    /** The 10-digit NUBAN for a prefix and serial. */
    public static String nuban(String prefix, long serial) {
        String nineDigits = prefix + String.format("%06d", serial);
        return nineDigits + checkDigit(nineDigits);
    }

    /** CBN check digit for an OFI account: weights 3,7,3 repeated over the institution code and the 9 digits. */
    static int checkDigit(String nineDigits) {
        String digits = INSTITUTION_CODE + nineDigits;
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * WEIGHTS[i % WEIGHTS.length];
        }
        return (10 - sum % 10) % 10;
    }

    static String sequenceName(String prefix) {
        return "nuban_serial_" + prefix;
    }

    private void createSequenceIfMissing(String sequence) {
        if (jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, sequence)) {
            return;
        }
        // Two first accounts on a new prefix at once: the lock makes the second wait, then find the sequence there.
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, sequence);
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS " + sequence + " MINVALUE 1 MAXVALUE " + MAX_SERIAL + " NO CYCLE");
    }

    private long nextSerial(String prefix, String sequence) {
        try {
            return jdbcTemplate.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        } catch (DataAccessException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("reached maximum value")) {
                throw new PlatformDataIntegrityException("error.msg.nuban.serials.exhausted",
                        "All " + MAX_SERIAL + " NUBAN serials for prefix " + prefix + " are used", e);
            }
            throw e;
        }
    }

    private boolean taken(String nuban) {
        return jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM m_savings_account WHERE account_no = ?)", Boolean.class, nuban);
    }
}

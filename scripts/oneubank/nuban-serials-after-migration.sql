-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements. See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership. The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License. You may obtain a copy of the License at
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied. See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- 1UBank: move each NUBAN prefix's serial counter (sequence nuban_serial_<prefix>) past every NUBAN already issued.
--
-- Run on the tenant database after TransactionPoint is frozen and its accounts are loaded, before new accounts
-- are opened:  psql -v ON_ERROR_STOP=1 -d <tenant db> -f nuban-serials-after-migration.sql
-- Safe to run again: a counter only ever moves forward.
--
-- Serials come from:
--   1. every 10-digit account number in m_savings_account (prefix = first 3 digits, serial = next 6);
--   2. NUBANs issued outside m_savings_account and TP's own counters: add them to nuban_issued_elsewhere below,
--      e.g. TP's shared counter (global config nuban-generator) for prefix 000, each product's NUBAN serial,
--      and the highest sub-ledger NUBAN serial per prefix.
-- Prefixes no product uses any more (e.g. TP's old 200, 100, 500) get counters too, so a product given one later
-- continues after them.

BEGIN;

CREATE TEMP TABLE nuban_issued_elsewhere (prefix varchar(3) NOT NULL, last_serial integer NOT NULL) ON COMMIT DROP;
-- INSERT INTO nuban_issued_elsewhere (prefix, last_serial) VALUES ('000', 1441), ('111', 33642);

DO $$
DECLARE
    r record;
    sequence_name text;
    issued bigint;
BEGIN
    FOR r IN
        SELECT prefix, max(last_serial) AS last_serial
        FROM (SELECT substr(account_no, 1, 3) AS prefix, substr(account_no, 4, 6)::integer AS last_serial
              FROM m_savings_account WHERE account_no ~ '^[0-9]{10}$'
              UNION ALL
              SELECT prefix, last_serial FROM nuban_issued_elsewhere
              UNION ALL
              SELECT nuban_prefix, 0 FROM m_savings_product WHERE nuban_prefix IS NOT NULL) s
        WHERE prefix ~ '^[0-9]{3}$'
        GROUP BY prefix
    LOOP
        sequence_name := 'nuban_serial_' || r.prefix;
        EXECUTE format('CREATE SEQUENCE IF NOT EXISTS %I MINVALUE 1 MAXVALUE 999999 NO CYCLE', sequence_name);
        EXECUTE format('SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM %I', sequence_name) INTO issued;
        IF r.last_serial > issued THEN
            PERFORM setval(sequence_name, r.last_serial);
            issued := r.last_serial;
        END IF;
        RAISE NOTICE 'prefix %: next serial %', r.prefix, issued + 1;
    END LOOP;
END
$$;

COMMIT;

# 1UBank patch list

Every change we make to an **existing Apache Fineract file** is recorded here. New files and modules we add do not need an entry.

Before upgrading to a new Fineract release, check each entry: has it been fixed upstream (drop our patch), or does it still need to be applied?

| # | Date | Upstream file(s) changed | Why | Our commit / PR | Upstream issue / PR | Status |
|---|---|---|---|---|---|---|
| 1 | 2026-11-08 | `fineract-provider/src/main/resources/application.properties` | Lists the `oneubank.auth-sync.*` settings (staff sync and first-login migration) with their environment variables and defaults, so they are visible next to Fineract's own settings. Adds lines only | | | `ours only` |

**Status values:** `ours only` · `sent upstream` · `merged upstream` (remove at next upgrade)

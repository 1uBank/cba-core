# 1UBank patch list

Every change we make to an **existing Apache Fineract file** is recorded here. New files and modules we add do not need an entry.

Before upgrading to a new Fineract release, check each entry: has it been fixed upstream (drop our patch), or does it still need to be applied?

| # | Date | Upstream file(s) changed | Why | Our commit / PR | Upstream issue / PR | Status |
|---|---|---|---|---|---|---|
| 1 | 2026-11-08 | `fineract-provider/src/main/resources/application.properties` | Lists the `oneubank.auth-sync.*` settings (staff sync and first-login migration) with their environment variables and defaults, so they are visible next to Fineract's own settings. Adds lines only | | | `ours only` |
| 2 | 2026-11-17 | `fineract-provider/.../security/config/OidcFederationSecurityConfig.java` | With OIDC federation on, the application did not start: the converter's `@Bean` method needed the configuration instance, which needed the converter. The method is now static | PR #9 | | `ours only` |
| 3 | 2026-11-18 | `fineract-provider/.../security/config/OidcFederationSecurityConfig.java` | The OIDC chain's `"/api/**"` string pattern became a Spring MVC matcher, which drops the Jersey servlet's `/api` prefix, so it never matched and every call went to the Basic chain. It now uses the same path matcher as `SecurityConfig` and takes only API calls with a Bearer token, since Fineract requires the Basic chain and refuses two chains with the same matcher | | | `ours only` |

**Status values:** `ours only` · `sent upstream` · `merged upstream` (remove at next upgrade)

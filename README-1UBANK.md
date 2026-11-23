# CBA-CORE: 1UBank core (Apache Fineract)

This repository is 1UBank's copy of [Apache Fineract](https://github.com/apache/fineract). It is **CBA-CORE**, the core of the 1UBank core banking application.


- **Base version:** Fineract `1.15.0` (upstream tag `1.15.0`)
- **Branches:** `dev`, `uat`, `main` (see "Branches and remotes"). All start from the `1.15.0` tag + our patches
- **Our changes:** listed in [PATCHES-1UBANK.md](PATCHES-1UBANK.md)

The upstream [README.md](README.md) is kept unchanged so that upstream updates merge cleanly. This file covers what is specific to 1UBank.

---

## 1. Run it locally

### Prerequisites

| Tool | Version | Check with |
|---|---|---|
| Java (JDK) | **21** (Azul Zulu or Eclipse Temurin) | `java -version` |
| Docker Desktop | Any recent version | `docker --version` |
| Git | Any recent version | `git --version` |
| Machine | 16 GB RAM recommended | |

> Fineract is built with a Java 21 toolchain. If you have several JDKs installed, make sure `JAVA_HOME` points to JDK 21.

### Step 1: Get the code

```bash
git clone https://github.com/1uBank/cba-core.git
cd cba-core
git switch dev
```

### Step 2: Start PostgreSQL

```bash
docker run --name fineract-postgres -p 5432:5432 \
  -e POSTGRES_USER=root -e POSTGRES_PASSWORD=postgres \
  -d postgres:18.3
```

These are **local-only** credentials and match Fineract's defaults (`root` / `postgres`). Never use them in any shared environment.

### Step 3: Create the two databases (first time only)

```bash
./gradlew createPGDB -PdbName=fineract_tenants
./gradlew createPGDB -PdbName=fineract_default
```

- `fineract_tenants` holds the list of tenants.
- `fineract_default` holds the bank data for the `default` tenant.

### Step 4: Start Fineract

```bash
export FINERACT_DEFAULT_TENANTDB_TIMEZONE=Africa/Lagos
./gradlew devRun
```

- `devRun` skips the code quality checks so startup is faster. Run the full build (section 3) before opening a pull request.
- The time zone is only applied when the `default` tenant is first created. Without it, Fineract's default is `Asia/Kolkata`.
- The first start takes a few minutes: it builds the code and creates all the tables.

### Step 5: Check that it is running

```bash
curl --insecure https://localhost:8443/fineract-provider/actuator/health
```

Expected: `{"status":"UP",...}`

Then try an authenticated call (default local login `mifos` / `password`, tenant `default`):

```bash
curl --insecure https://localhost:8443/fineract-provider/api/v1/clients \
  -H 'Fineract-Platform-TenantId: default' \
  -u mifos:password
```

Expected on a fresh database: `{"totalFilteredRecords":0,"pageItems":[]}`

- **API documentation:** https://localhost:8443/fineract-provider/swagger-ui/index.html
- **Back-office UI:** run the `cba-web-app` repository (see its `README-1UBANK.md`).

### Stopping and starting again

```bash
# Stop Fineract: Ctrl+C in the terminal running devRun
docker stop fineract-postgres     # stop the database (data is kept)
docker start fineract-postgres    # start it again next time, then ./gradlew devRun
```

To start again from an empty database:

```bash
docker rm -f fineract-postgres
```

Then repeat steps 2 to 4.

---

## 2. Common problems

| Problem | Fix |
|---|---|
| `Port 5432 is already in use` | Another PostgreSQL is running. Stop it, or stop the old container: `docker ps`, then `docker stop <name>`. |
| `Port 8443 is already in use` | Fineract is already running in another terminal. Stop it first. |
| `database "fineract_tenants" already exists` | Normal after the first time. Skip step 3. |
| Build fails with a Java version error | Check `java -version` and `JAVA_HOME`. Fineract needs JDK 21. |
| Browser shows a certificate warning | Expected locally: Fineract uses a self-signed certificate. Open https://localhost:8443 once and accept it. |
| Fineract stops with an out-of-memory error | Give Docker and your machine more memory, and close other heavy apps. |

---

## 3. Making changes

We follow these rules so we can keep taking Fineract updates:

1. **Add, don't modify.** Prefer new modules, new tables, new endpoints, external events and replaceable Spring beans over editing Fineract's files.
2. **Bug fixes in Fineract code** are a small change to the original file. Never copy a Fineract file to change it.
3. **Every change to an upstream file is listed** in [PATCHES-1UBANK.md](PATCHES-1UBANK.md), with the reason. Where it makes sense, the fix is also sent upstream as a pull request to Apache Fineract.
4. **Before a pull request**, run the full build with the quality checks:

   ```bash
   ./gradlew build -x test
   ```

### CI/CD

Upstream's GitHub Actions workflows, Dependabot config and contributor templates (`.github/`) were removed so that none of upstream's pipelines run on our repository or use our Actions minutes. DevOps adds our own pipelines under `.github/workflows/`.

When merging a new upstream release, git may report conflicts on those deleted files: keep them deleted.

### Branches and remotes

| Branch | Holds | Deploys to |
|---|---|---|
| `dev` | Day-to-day work; feature branches merge here by pull request | dev |
| `uat` | Promoted from `dev` when a batch is ready for testing | UAT |
| `main` | Promoted from `uat` after sign-off: what is live | production |

Create feature branches from `dev` and merge back by pull request. Changes reach `uat` and `main` only by promotion (`dev` → `uat` → `main`), never by committing to them directly.

| Remote | Points to | Used for |
|---|---|---|
| `origin` | the bank's repository | Our work. Push here. |
| `upstream` | `apache/fineract` | Fetching new Fineract releases. Never push here. |

To add the upstream remote after cloning:

```bash
git remote add upstream https://github.com/apache/fineract.git
```

Also stop accidental pushes to upstream (fetching still works):

```bash
git remote set-url --push upstream DISABLED
```

---

## 4. Deployment

### What gets deployed

| Environment | Purpose | Data |
|---|---|---|
| `dev` | Engineers test merged work | Test data only |
| `uat` | Testers and client teams test before release | Test data only |
| `prod` | Live customers | Real data |

Each environment has its own copy of everything below. Every environment runs **the same container image**; only the configuration changes.

```
                         ┌──────────────── one domain, e.g. cba-uat.<domain> ────────────────┐
 Staff browser ──HTTPS──►│ Load balancer (TLS)                                                │
                         │   /fineract-provider/*  ──HTTP 8080──►  CBA-CORE container         │
                         │   everything else       ──HTTP 80────►  cba-web-app container      │
                         └────────────────────────────────────────────────────────────────────┘
                              CBA-CORE ──► PostgreSQL (fineract_tenants, fineract_default)
                              CBA-CORE ──► S3 bucket (client photos and documents)
```

Set up a new environment in the order below. Steps 1 to 3 are done once per environment; steps 4 to 7 are repeated for every release (see "Releasing a new version").

### Step 1: Create the database

CBA-CORE needs its own **PostgreSQL 18** instance (the version we develop and test against), with:

- Private subnets only; the security group accepts port 5432 **from CBA-CORE only**.
- Encryption at rest and automated backups. In `prod`: Multi-AZ and point-in-time recovery.

Then, connected as the instance's master user, create the application user and the two databases:

```sql
CREATE USER cba_core WITH PASSWORD '<application database password>';
CREATE DATABASE fineract_tenants OWNER cba_core;
CREATE DATABASE fineract_default OWNER cba_core;
```

- `fineract_tenants` holds the list of tenants and their connection details.
- `fineract_default` holds the bank's data (customers, accounts, transactions, GL).
- Leave both databases **empty**. CBA-CORE creates every table itself on its first start.
- CBA-CORE connects as `cba_core`, never as the master user.

### Step 2: Create the secrets

In the secrets manager, create for this environment:

| Secret | Used by |
|---|---|
| Application database password (from step 1) | `FINERACT_HIKARI_PASSWORD` and `FINERACT_DEFAULT_TENANTDB_PWD` |
| Master password: a long random value | `FINERACT_DEFAULT_TENANTDB_MASTER_PASSWORD` and `FINERACT_DEFAULT_MASTER_PASSWORD` (same value) |
| Secret of the auth service's `user-provisioner` client (same value as `USER_PROVISIONER_SECRET` in `cba-auth`) | `ONEUBANK_AUTHSYNC_CLIENTSECRET` |

The master password encrypts the tenant database password that CBA-CORE stores in `fineract_tenants`. **It must never change after the first start**, or CBA-CORE can no longer decrypt it and won't start. Each environment has its own value. To change the application database password later, follow "Rotating the database password" below; updating the secret alone breaks CBA-CORE.

### Step 3: Create the S3 bucket and IAM role

Uploaded files (client photos, KYC documents, signatures) are stored **outside the database**, and the container's own disk is wiped on every deployment, so every environment stores them in S3.

- One bucket per environment, e.g. `<prefix>-cba-core-documents-uat`.
- **Block all public access**; encryption at rest; **versioning on**, so an overwritten or deleted file can be recovered.
- An **IAM role for the CBA-CORE container** (task role) with read, write and delete on that bucket only. CBA-CORE uses the role automatically; no access keys are created.

### Step 4: Build and push the image

```bash
./gradlew :fineract-provider:jib -Djib.to.image=<registry>/cba-core:<git-sha>
```

- cba-core has **no Dockerfile**. Jib, a Gradle plugin, builds the image from the Gradle build itself and pushes it straight to the registry, so the build machine doesn't need Docker installed. The result is an ordinary container image, deployed like any other.
- Tag with the **git commit**, never only `latest`, so every deployment can be traced to its code.
- Base image `azul/zulu-openjdk-alpine:21`; the container runs as user `nobody`.
- The start command is built into the image. Nothing is typed to start it.

To build into a local Docker daemon instead: `./gradlew :fineract-provider:jibDockerBuild` (image `fineract:latest`).

### Step 5: Create the CBA-CORE service

| Setting | Value |
|---|---|
| Image | `<registry>/cba-core:<git-sha>` from step 4 |
| Container port | `8080` |
| CPU / memory | 2 vCPU / 4 GB |
| Number of containers | **1**. Running more than one needs the manager/worker split (`FINERACT_MODE_*` variables), which is not set up. |
| Task role | The IAM role from step 3 |
| Environment variables | Every variable in "Configuration reference" below, with the secrets from step 2 injected from the secrets manager |

### Step 6: Set up the load balancer

- HTTPS listener with the environment's certificate. TLS ends here; traffic to the container is plain HTTP inside the private network.
- Target group for CBA-CORE: HTTP, port `8080`, health check path `/fineract-provider/actuator/health/readiness`.
- Routing rule: path `/fineract-provider/*` goes to the CBA-CORE target group. Everything else goes to the web-app (see the `cba-web-app` README).
- Health check grace period: **at least 10 minutes**, so the first start (step 7) isn't killed for being slow.

### Step 7: First start and checks

On the first start against an empty database, CBA-CORE creates and migrates all its tables. This takes several minutes, and it reports ready only when it's finished. Follow the container logs; the start is complete when the log shows `Started ServerApplication`.

Then check:

```bash
# 1. Ready
curl https://<environment-domain>/fineract-provider/actuator/health/readiness
# -> {"status":"UP"}

# 2. Can sign in (dev / uat only)
curl -X POST https://<environment-domain>/fineract-provider/api/v1/authentication \
  -H 'Fineract-Platform-TenantId: default' -H 'Content-Type: application/json' \
  -d '{"username":"mifos","password":"password"}'
# -> JSON containing "authenticated": true
```

### Step 8 (`prod` only): Lock down the default login

Fineract creates an administrator `mifos` with password `password` on a new database. In `dev` and `uat` it stays, so engineers and testers can sign in. In `prod`, **before the environment is opened to anyone**:

1. Sign in once as `mifos` and immediately change its password to a long random value. Store it in the secrets manager as a break-glass credential.
2. Create named administrator accounts for the people who need them (no shared accounts).
3. Sign in as one of the named administrators and **disable or delete** the `mifos` user (Admin → Users).
4. Confirm that signing in with `mifos` / `password` fails, and record the check in the go-live checklist.

### Sign-in modes

Two settings choose how callers sign in to the API:

| Mode | `FINERACT_SECURITY_BASICAUTH_ENABLED` | `FINERACT_SECURITY_OIDC_FEDERATION_ENABLED` | Accepts |
|---|---|---|---|
| Basic only | `true` (default) | `false` (default) | Username and password |
| Both | `true` | `true` | Tokens from the auth service on calls with a Bearer token; username and password on the rest |
| Auth service only | `false` | `true` | Tokens from the auth service only |

"Both" is for moving staff and services to the auth service: anyone who still has a CBA-CORE password can call the API with it, without MFA. Once everyone signs in through the auth service, use "Auth service only".

### Releasing a new version

1. Build and push the new image (step 4).
2. **`prod` only:** take a database snapshot.
3. Update the service to the new image tag.
4. On start, CBA-CORE applies any new database migrations automatically, then reports ready.
5. Repeat the checks in step 7.

**Rolling back:** redeploy the previous image tag. If the release included database migrations, the previous image may not run against the migrated database; restore the snapshot taken before the release first.

### Account numbers (NUBAN)

A savings product's **NUBAN prefix** (3 digits, set on the product screen) makes every account opened on it a NUBAN: the prefix, a 6-digit serial and the CBN check digit for institution code 090605. Products without a prefix keep Fineract's own numbering. Each prefix has its own serial counter, the database sequence `nuban_serial_<prefix>`, shared by every product with that prefix. The prefix cannot change once the product has accounts.

**After loading TransactionPoint's accounts**, and before any new account is opened, move the counters past every NUBAN already issued:

```bash
psql -v ON_ERROR_STOP=1 -d <tenant database> -f scripts/oneubank/nuban-serials-after-migration.sql
```

First add TP's own counters and any NUBANs held outside savings accounts (sub-ledgers) to the `nuban_issued_elsewhere` insert at the top of the script. The script can be run again; counters only move forward.

### Rotating the database password

CBA-CORE uses the application database password in two places:

1. To connect to `fineract_tenants`: read from `FINERACT_HIKARI_PASSWORD` on every start.
2. To connect to `fineract_default`: read from the `default` tenant's row in `fineract_tenants.tenant_server_connections`, column `schema_password`, **encrypted with the master password**. This row is created from `FINERACT_DEFAULT_TENANTDB_PWD` on the **first start only**. After that, CBA-CORE never reads `FINERACT_DEFAULT_TENANTDB_PWD` again.

So changing the password in the secrets manager alone breaks CBA-CORE: it starts, connects to `fineract_tenants` with the new password, then fails to connect to `fineract_default` with the old one stored in the row. Rotate it in this order, and do steps 3 to 5 back to back: from step 3, CBA-CORE can't open any new database connection until it restarts with the new value.

1. **Encrypt the new password** with the environment's master password, using the image that is currently deployed. First load both values into the shell without typing them on the command line (each `read` waits for you to paste the value; nothing is shown or saved in the history):

   ```bash
   read -rs MASTER_PASSWORD && export MASTER_PASSWORD   # paste the master password, press Enter
   read -rs NEW_PASSWORD && export NEW_PASSWORD         # paste the new database password, press Enter
   ```

   Then run:

   ```bash
   docker run --rm -e MASTER_PASSWORD -e NEW_PASSWORD --entrypoint sh <registry>/cba-core:<deployed-git-sha> -c \
     'java -cp "/app/resources:/app/classes:/app/libs/*" \
        org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor "$MASTER_PASSWORD" "$NEW_PASSWORD"'
   ```

   Keep the line `The encrypted password: …`. Ignore the hash line; the stored hash stays as it is because the master password hasn't changed.

2. **Update the secret** in the secrets manager to the new password.
3. **Change the password in PostgreSQL** (as the master user): `ALTER USER cba_core WITH PASSWORD '<new password>';`
4. **Update the tenant row** (connected to `fineract_tenants`):

   ```sql
   UPDATE tenant_server_connections SET schema_password = '<encrypted password from step 1>' WHERE id = 1;
   ```

5. **Restart CBA-CORE** and repeat the checks in step 7.

The same applies to the other `FINERACT_DEFAULT_TENANTDB_*` values (host, port, username, database name): after the first start, change them in `tenant_server_connections` (`schema_server`, `schema_server_port`, `schema_username`, `schema_name`), not only in the environment variables.

**The master password itself can't be rotated this way.** `master_password_hash` in the same row is a hash of the master password, and CBA-CORE refuses to start (`Invalid master password on tenant connection 1.`) if the configured master password doesn't match it. Keep the master password unchanged for the life of the environment.

### Configuration reference

Set these as **environment variables** on the container. Values marked **Secret** come from the secrets manager and must never be written in code, images, task definitions or tickets.

**Database**

| Variable | Value | Secret |
|---|---|---|
| `FINERACT_HIKARI_JDBC_URL` | `jdbc:postgresql://<database-host>:5432/fineract_tenants` | |
| `FINERACT_HIKARI_USERNAME` | `cba_core` | |
| `FINERACT_HIKARI_PASSWORD` | Application database password | **Secret** |
| `FINERACT_DEFAULT_TENANTDB_HOSTNAME` | `<database-host>` | |
| `FINERACT_DEFAULT_TENANTDB_PORT` | `5432` | |
| `FINERACT_DEFAULT_TENANTDB_UID` | `cba_core` | |
| `FINERACT_DEFAULT_TENANTDB_PWD` | Application database password | **Secret** |
| `FINERACT_DEFAULT_TENANTDB_NAME` | `fineract_default` | |
| `FINERACT_DEFAULT_TENANTDB_IDENTIFIER` | `default` | |
| `FINERACT_DEFAULT_TENANTDB_TIMEZONE` | `Africa/Lagos` (applied only when the tenant is first created) | |
| `FINERACT_DEFAULT_TENANTDB_DESCRIPTION` | `1UBank` | |
| `FINERACT_DEFAULT_TENANTDB_MASTER_PASSWORD` | Master password (step 2) | **Secret** |
| `FINERACT_DEFAULT_MASTER_PASSWORD` | Master password (step 2) | **Secret** |

**Server**

| Variable | Value |
|---|---|
| `FINERACT_SERVER_SSL_ENABLED` | `false` (TLS ends at the load balancer) |
| `FINERACT_SERVER_PORT` | `8080` |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` (lets Java use 3 GB of the 4 GB; the default is only a quarter) |

**Security**

| Variable | `dev` / `uat` | `prod` |
|---|---|---|
| `FINERACT_SECURITY_CORS_ALLOWED_ORIGIN_PATTERNS` | `https://<environment-domain>` | `https://<prod-domain>` (the default `*` allows any site) |
| `FINERACT_INSECURE_HTTP_CLIENT` | `false` | `false` (the default `true` skips certificate checks on outgoing calls) |
| `SPRINGDOC_API_DOCS_ENABLED`, `SPRINGDOC_SWAGGER_UI_ENABLED` | `true` (handy for testing) | `false` |
| `FINERACT_MANAGEMENT_ENDPOINT_WEB_EXPOSURE_INCLUDE` | `health,info,prometheus` | `health` (plus `prometheus` only if monitoring scrapes it on a private network) |

**File storage**

| Variable | Value |
|---|---|
| `FINERACT_CONTENT_FILESYSTEM_ENABLED` | `false` |
| `FINERACT_CONTENT_S3_ENABLED` | `true` |
| `FINERACT_CONTENT_S3_BUCKET_NAME` | The bucket from step 3 |
| `FINERACT_CONTENT_S3_REGION` | The bucket's AWS region |
| `FINERACT_CONTENT_S3_ACCESS_KEY`, `FINERACT_CONTENT_S3_SECRET_KEY` | **Leave unset.** CBA-CORE then uses the task role. |
| `FINERACT_CONTENT_S3_ENDPOINT` | Leave unset on AWS. Only for S3-compatible storage elsewhere (e.g. MinIO). |

Accepted uploads are limited by `FINERACT_CONTENT_REGEX_WHITELIST` and `FINERACT_CONTENT_MIME_WHITELIST` (default: PDF, Word, Excel, JPEG, PNG).

**Health checks**

| Check | Path |
|---|---|
| Liveness | `/fineract-provider/actuator/health/liveness` |
| Readiness | `/fineract-provider/actuator/health/readiness` |

**Auth service** (code in `fineract-provider`, package `org.apache.fineract.oneubank.authsync`)

Admins keep creating, editing and deleting staff in Fineract's user screen. After each change is saved, this module creates, updates or disables the same staff user in the auth service (`cba-auth`, realm `cba`):

- **New staff** are created there linked to the auth service's first-login migration: their first sign-in checks the password the admin set here, then they choose their own and set up an authenticator app.
- **A password an admin sets here** (the portal's Reset Password) works the same way: the staff member is signed out, their next sign-in checks the new password, then they choose their own.
- **Disabled or deleted staff** are disabled there and signed out.

It also provides:

| Endpoint | For | Permission |
|---|---|---|
| `/v1/oneubank/staff-migration/{username}` and `/verify` | The auth service, to move existing staff across at their first login | Only the Fineract user `service-account-user-migrator` |
| `GET /v1/oneubank/staff/{userId}/sign-in` | The portal: whether the staff member has an authenticator app and is locked out | `READ_USER` |
| `POST /v1/oneubank/staff/{userId}/sign-in/reset-authenticator` | The portal: removes the authenticator app (lost phone); a new one is set up at the next sign-in | `UPDATE_USER` |
| `POST /v1/oneubank/staff/{userId}/sign-in/unlock` | The portal: lifts the lock-out after too many failed sign-ins | `UPDATE_USER` |

| Variable | Value | Secret |
|---|---|---|
| `ONEUBANK_AUTHSYNC_ENABLED` | `true` (off by default) | |
| `ONEUBANK_AUTHSYNC_BASEURL` | The auth service's **internal** URL, for example `http://cba-auth.internal:8080`. Not its public URL: the sync uses the auth service's admin API, which is reachable only on the private network | |
| `ONEUBANK_AUTHSYNC_REALM` | `cba` (default) | |
| `ONEUBANK_AUTHSYNC_CLIENTID` | `user-provisioner` (default) | |
| `ONEUBANK_AUTHSYNC_CLIENTSECRET` | Secret of `user-provisioner` | **Secret** |
| `ONEUBANK_AUTHSYNC_SENDSETUPEMAIL` | `false` (default). `true` emails new staff a setup link; needs email (SMTP) set up in the auth service's `cba` realm | |
| `ONEUBANK_AUTHSYNC_MIGRATORUSERNAME` | `service-account-user-migrator` (default) | |
| `ONEUBANK_AUTHSYNC_TIMEOUTSECONDS` | `10` (default) | |
| `ONEUBANK_AUTHSYNC_REQUIREDACTIONS` | `UPDATE_PASSWORD,CONFIGURE_TOTP` (default): what a new staff member must do at first sign-in | |
| `ONEUBANK_AUTHSYNC_SERVICEACCOUNTPREFIX` | `service-account-` (default): usernames that are never synced | |
| `ONEUBANK_AUTHSYNC_MIGRATIONCOMPONENTNAME` | `cba-core-user-migration` (default): the auth service's first-login migration, which new staff are linked to. `user-provisioner` needs the `view-realm` role to find it | |

These settings and their defaults are also listed in `fineract-provider/src/main/resources/application.properties`, under `oneubank.auth-sync.*`.

The platform services that call CBA-CORE with auth service tokens need a CBA-CORE user each, named as in their tokens (`service-account-<clientId>`). A migration creates them on every environment at the first start, in Head Office (`fineract-provider/src/main/resources/db/custom-changelog/0001_oneubank_service_users.xml`). They are never synced to the auth service, because they are clients there, not staff, and neither has a usable password, so neither can sign in with one:

| User | Role |
|---|---|
| `service-account-banking-api` | `Banking API`: `CHECKER_SUPER_USER`, so its postings skip maker-checker, plus the permissions its calls need, each added by a new changeset when the endpoint that needs it is built |
| `service-account-user-migrator` | None |

A new service user or permission is a new changeset in a new file under `db/custom-changelog/`; never edit one that has run.

To run it locally, start the auth service from `cba-auth` (see its README), then start CBA-CORE with:

```bash
export ONEUBANK_AUTHSYNC_ENABLED=true
export ONEUBANK_AUTHSYNC_BASEURL=http://localhost:8180
export ONEUBANK_AUTHSYNC_CLIENTSECRET=<user-provisioner secret, from the auth service admin console>
./gradlew devRun
```

**TLS inside the container** (only if the load balancer can't terminate TLS): `FINERACT_SERVER_SSL_ENABLED=true`, `FINERACT_SERVER_PORT=8443`, and our own keystore via `FINERACT_SERVER_SSL_KEY_STORE` and `FINERACT_SERVER_SSL_KEY_STORE_PASSWORD` (**Secret**). Never use the image's built-in self-signed keystore (password `openmf`).

### Never in production

- `./gradlew devRun` (skips quality checks; for laptops only)
- Default passwords: `mifos`/`password`, the `root`/`postgres` database login, master password `fineract`, keystore password `openmf`
- Connecting as the database master user
- Swagger UI open to the internet
- `FINERACT_INSECURE_HTTP_CLIENT=true`
- Any real customer data in `dev` or `uat`
- File storage on the container's disk (`FINERACT_CONTENT_FILESYSTEM_ENABLED=true`): files are lost on the next deployment

---

## 5. Licence

Apache Fineract is licensed under the Apache License 2.0. Keep the licence and notice files (`LICENSE_SOURCE`, `LICENSE_RELEASE`, `NOTICE_SOURCE`, `NOTICE_RELEASE`, `APACHE_LICENSETEXT.md`) unchanged.

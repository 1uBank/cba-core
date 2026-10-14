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

The master password encrypts the tenant database password that CBA-CORE stores in `fineract_tenants`. **It must never change after the first start**, or CBA-CORE can no longer decrypt it and won't start. Each environment has its own value.

### Step 3: Create the S3 bucket and IAM role

Uploaded files (client photos, KYC documents, signatures) are stored **outside the database**, and the container's own disk is wiped on every deployment, so every environment stores them in S3.

- One bucket per environment, e.g. `<prefix>-cba-core-documents-uat`.
- **Block all public access**; encryption at rest; **versioning on**, so an overwritten or deleted file can be recovered.
- An **IAM role for the CBA-CORE container** (task role) with read, write and delete on that bucket only. CBA-CORE uses the role automatically; no access keys are created.

### Step 4: Build and push the image

```bash
./gradlew :fineract-provider:jib -Djib.to.image=<registry>/cba-core:<git-sha>
```

- Jib builds the image and pushes it straight to the registry. No Dockerfile and no Docker daemon are needed.
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

When staff sign in through an identity provider, basic-auth sign-in can be switched off entirely with `FINERACT_SECURITY_BASICAUTH_ENABLED=false`.

### Releasing a new version

1. Build and push the new image (step 4).
2. **`prod` only:** take a database snapshot.
3. Update the service to the new image tag.
4. On start, CBA-CORE applies any new database migrations automatically, then reports ready.
5. Repeat the checks in step 7.

**Rolling back:** redeploy the previous image tag. If the release included database migrations, the previous image may not run against the migrated database; restore the snapshot taken before the release first.

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

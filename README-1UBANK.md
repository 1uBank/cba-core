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

> **Status:** draft for review with DevOps. Hosting (AWS for now; CBN data-localisation decision due January 2027), the container platform and TLS setup are still to be confirmed. Update this section when they are.

### Environments

| Environment | Purpose | Data |
|---|---|---|
| `dev` | Engineers test merged work | Test data only |
| `uat` | Testers and client teams test before release | Test data only |
| `prod` | Live customers | Real data |

Every environment runs **the same container image**. Only the configuration changes.

### Build the image

```bash
# Build into the local Docker daemon (image: fineract:<version> and fineract:latest)
./gradlew :fineract-provider:jibDockerBuild

# Or build and push straight to the registry (no Docker needed), tagged with the git commit
./gradlew :fineract-provider:jib -Djib.to.image=<registry>/cba-core:<git-sha>
```

- Base image: `azul/zulu-openjdk-alpine:21`; runs as user `nobody`.
- The container listens on **8443** (HTTPS) or **8080** (HTTP), depending on the TLS settings below.
- Tag images with the **git commit**, never only `latest`, so every deployment can be traced to its code.
- **No start command to type:** Jib builds the start command into the image (it runs `org.apache.fineract.ServerApplication`). DevOps supplies only the image, the environment variables below and the port mapping.

### Configuration

Set these as **environment variables** on the container. Values marked **Secret** come from the secrets manager and must never be written in code, images, task definitions or tickets.

**Database (CBA-CORE has its own PostgreSQL cluster)**

| Variable | Example / rule | Secret |
|---|---|---|
| `FINERACT_HIKARI_JDBC_URL` | `jdbc:postgresql://<host>:5432/fineract_tenants` | |
| `FINERACT_HIKARI_USERNAME` | Application database user (not the superuser) | |
| `FINERACT_HIKARI_PASSWORD` | | **Secret** |
| `FINERACT_DEFAULT_TENANTDB_HOSTNAME` | Same host as above | |
| `FINERACT_DEFAULT_TENANTDB_PORT` | `5432` | |
| `FINERACT_DEFAULT_TENANTDB_UID` | Application database user | |
| `FINERACT_DEFAULT_TENANTDB_PWD` | | **Secret** |
| `FINERACT_DEFAULT_TENANTDB_NAME` | `fineract_default` | |
| `FINERACT_DEFAULT_TENANTDB_IDENTIFIER` | `default` | |
| `FINERACT_DEFAULT_TENANTDB_TIMEZONE` | `Africa/Lagos` (applied only when the tenant is first created) | |
| `FINERACT_DEFAULT_TENANTDB_DESCRIPTION` | `1UBank` | |
| `FINERACT_DEFAULT_TENANTDB_MASTER_PASSWORD` | Encrypts the tenant DB password stored in the database. **Never the default `fineract`** in `uat`/`prod`. Must not change once set (or the stored password can't be decrypted). | **Secret** |
| `FINERACT_DEFAULT_MASTER_PASSWORD` | Same rule as above | **Secret** |

**Security**

| Variable | `dev` / `uat` | `prod` |
|---|---|---|
| `FINERACT_SECURITY_CORS_ALLOWED_ORIGIN_PATTERNS` | The portal's URL for that environment | The production portal URL only (default `*` allows any site) |
| `FINERACT_INSECURE_HTTP_CLIENT` | `false` | `false` (default `true` skips certificate checks on outgoing calls) |
| `SPRINGDOC_API_DOCS_ENABLED`, `SPRINGDOC_SWAGGER_UI_ENABLED` | `true` (handy for testing) | `false` |
| `FINERACT_MANAGEMENT_ENDPOINT_WEB_EXPOSURE_INCLUDE` | `health,info,prometheus` | `health` (plus `prometheus` only if monitoring scrapes it on a private network) |

**TLS (to be confirmed with DevOps)**

Pick one:

- **TLS ends at the load balancer** (usual on AWS): `FINERACT_SERVER_SSL_ENABLED=false`, `FINERACT_SERVER_PORT=8080`. The load balancer holds the certificate; traffic from it to Fineract stays inside the private network.
- **TLS in the container:** `FINERACT_SERVER_SSL_ENABLED=true` with our own keystore via `FINERACT_SERVER_SSL_KEY_STORE` and `FINERACT_SERVER_SSL_KEY_STORE_PASSWORD` (**Secret**). Never use the built-in self-signed keystore (password `openmf`) outside a laptop.

### File storage (client photos and documents)

Fineract stores uploaded files (client photos, KYC documents, signatures, attachments) **outside the database**. The database only keeps a pointer to each file.

By default, files go to a folder **inside the container** (`/tmp/.fineract` in our image). Containers are replaced on every deployment and restart, so **those files would be lost**. Every deployed environment must therefore use S3:

| Variable | `dev` / `uat` / `prod` |
|---|---|
| `FINERACT_CONTENT_FILESYSTEM_ENABLED` | `false` |
| `FINERACT_CONTENT_S3_ENABLED` | `true` |
| `FINERACT_CONTENT_S3_BUCKET_NAME` | One bucket per environment, e.g. `<prefix>-cba-core-documents-uat` |
| `FINERACT_CONTENT_S3_REGION` | The bucket's AWS region |
| `FINERACT_CONTENT_S3_ACCESS_KEY`, `FINERACT_CONTENT_S3_SECRET_KEY` | **Leave empty.** Fineract then uses the container's IAM role. Never store AWS keys. |
| `FINERACT_CONTENT_S3_ENDPOINT` | Leave empty on AWS. Only for S3-compatible storage elsewhere (e.g. MinIO in a Nigerian data centre). |

Bucket requirements:

- **Private**: block all public access. Staff see files only through CBA-CORE.
- **Encrypted at rest**, with **versioning** on, so an overwritten or deleted file can be recovered.
- **Access only from CBA-CORE's IAM role** (read, write, delete on that bucket).
- **Location**: KYC documents are customer data. The bucket's location is part of the CBN data-localisation decision, together with the database.

Accepted uploads are limited by `FINERACT_CONTENT_REGEX_WHITELIST` and `FINERACT_CONTENT_MIME_WHITELIST` (default: PDF, Word, Excel, JPEG, PNG). Keep the defaults unless Operations needs another type.

On a laptop the default folder (`~/.fineract`) is fine.

### Health checks

| Check | Path |
|---|---|
| Liveness | `/fineract-provider/actuator/health/liveness` |
| Readiness | `/fineract-provider/actuator/health/readiness` |

Allow several minutes on the **first** start of a new database: Fineract creates and migrates all tables before it reports ready.

### Default `mifos` / `password` login

Fineract creates an administrator `mifos` with password `password` on a new database.

- **`dev` and `uat`:** keep it, so engineers and testers can sign in. These environments hold test data only.
- **`prod`:** turn it off **before the environment is opened to anyone**:
  1. Sign in once as `mifos` and immediately change its password to a long random value (store it in the secrets manager as a break-glass credential).
  2. Create named administrator accounts for the people who need them (no shared accounts).
  3. Sign in as one of the named administrators and **disable or delete** the `mifos` user (Admin → Users).
  4. Confirm that signing in with `mifos` / `password` fails, and record the check in the go-live checklist.

Once staff sign in through the identity provider (decision pending), basic-auth sign-in can be switched off entirely with `FINERACT_SECURITY_BASICAUTH_ENABLED=false`.

### Never in production

- `./gradlew devRun` (skips quality checks; for laptops only)
- Default passwords: `mifos`/`password`, the `root`/`postgres` database login, master password `fineract`, keystore password `openmf`
- Swagger UI open to the internet
- `FINERACT_INSECURE_HTTP_CLIENT=true`
- Any real customer data in `dev` or `uat`
- File storage on the container's disk (`FINERACT_CONTENT_FILESYSTEM_ENABLED=true`): files are lost on the next deployment

---

## 5. Licence

Apache Fineract is licensed under the Apache License 2.0. Keep the licence and notice files (`LICENSE_SOURCE`, `LICENSE_RELEASE`, `NOTICE_SOURCE`, `NOTICE_RELEASE`, `APACHE_LICENSETEXT.md`) unchanged.

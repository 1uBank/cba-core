# 1UBank Core Ledger (Apache Fineract)

This repository is 1UBank's copy of [Apache Fineract](https://github.com/apache/fineract). It is the **Core Ledger** of the 1UBank core banking application.

- **Base version:** Fineract `1.15.0` (upstream tag `1.15.0`)
- **Working branch:** `1ubank/1.15` = the `1.15.0` tag + our patches
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
git clone https://github.com/<bank-org>/fineract.git
cd fineract
git switch 1ubank/1.15
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
- **Back-office UI:** run the `web-app` repository (see its `README-1UBANK.md`).

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

### Branches and remotes

| Remote | Points to | Used for |
|---|---|---|
| `origin` | the bank's repository | Our work. Push here. |
| `upstream` | `apache/fineract` | Fetching new Fineract releases. Never push here. |

To add the upstream remote after cloning:

```bash
git remote add upstream https://github.com/apache/fineract.git
```

---

## 4. Licence

Apache Fineract is licensed under the Apache License 2.0. Keep the licence and notice files (`LICENSE_SOURCE`, `LICENSE_RELEASE`, `NOTICE_SOURCE`, `NOTICE_RELEASE`, `APACHE_LICENSETEXT.md`) unchanged.

# Continuum: backend

**An internal-audit and continuous-controls platform for banks, built as a modular Spring Boot monolith.**

Continuum gives a bank's audit and risk teams one place to run audit work, track controls and keep a
trustworthy record of who did what. One Java 21 application serves both front-ends (the functional
platform and the system-admin console). The separation is enforced by security (token audiences
`continuum-app` and `continuum-admin`), not by duplicating code.

## What it does

- **Fine-grained authorization**: a feature catalogue, a central `can()` decision point and
  segregation-of-duties rules (`iam`), applied with `@PreAuthorize` on every method.
- **Tamper-evident audit trail**: a hash-chained journal signed with an HMAC key kept in a secrets
  vault (`audittrail`).
- **Multi-tenancy**: each bank (tenant) configures its own business rules in the database, with no
  redeployment (`tenancy`).
- **Continuous controls monitoring** (`ccm`), **workflows** (`workflow`), **e-signature** (`signature`),
  **notifications**, **reporting**, and a **core-banking connector layer** (`connectors`).
- **PostgreSQL or Oracle** from the same code base, with a dedicated Oracle parity test suite.
- **Standards-based sign-in**: OIDC with Keycloak, MFA-aware (`acr >= 2` required on admin APIs), plus an
  authentication BFF that keeps refresh tokens server-side.

## Engineering choices worth a look

- **Architecture rules are tested.** Spring Modulith modules may only talk through their public `*.api`
  package or through events. `ModularityTests` (ArchUnit) fails the build if a module reaches into
  another module's entities or repositories.
- **Fail-closed start-up.** The backend checks the database schema at start-up and refuses to run on a
  missing, drifted or outdated schema (`CTN-DB-0001`, `CTN-DB-0002`, `CTN-DB-0003`).
- **Migrations are separate.** Schema changes are applied by
  [`continuum-migrator`](https://github.com/hassanlahlami/continuum-migrator), never by the running
  backend.
- **Deployment surfaces.** The same jar can run as `app`, `admin`, `worker` (scheduled controls only) or
  `all`, so a replicated API never duplicates background jobs.
- **Operations built in.** RFC 9457 problem+json errors with a trace id, structured JSON logs, graceful
  shutdown, container health probes and a Prometheus endpoint.

## Modules

`iam`, `audittrail`, `tenancy`, `ccm`, `admin`, `audit`, `connectors`, `workflow`, `signature`,
`notification`, `reporting`, `aigateway` (post-MVP).

## Related repositories

| Repository | Role |
| --- | --- |
| [continuum-migrator](https://github.com/hassanlahlami/continuum-migrator) | Database migrations (PostgreSQL / Oracle) |
| [continuum-auth](https://github.com/hassanlahlami/continuum-auth) | Authentication service |
| [continuum-keycloak-spi](https://github.com/hassanlahlami/continuum-keycloak-spi) | Keycloak extension |
| [continuum-app-web](https://github.com/hassanlahlami/continuum-app-web) | Functional front-end (TypeScript) |
| [continuum-admin-web](https://github.com/hassanlahlami/continuum-admin-web) | Admin front-end (TypeScript) |

## Build and test

Requires Java 21 and Maven 3.9.

```bash
mvn -o verify          # build + unit tests (no database needed)

# Integration tests against a real database (skipped otherwise):
mvn -o surefire:test -Dtest=<TestClass> \
  -Dit.db.url=jdbc:postgresql://localhost:5432/<db> -Dit.db.user=<user> -Dit.db.password=<password>

# Oracle parity tests:
#   -Dit.oracle.url=jdbc:oracle:thin:@host:1521/FREEPDB1 -Dit.oracle.user=... -Dit.oracle.password=...
```

Run the Oracle tests on their own, apart from the Spring context tests, on machines with limited memory.

## Run

Run the migrator first; the backend refuses to start on an unmigrated database.

```bash
CONTINUUM_DB_URL=jdbc:postgresql://localhost:5432/continuum \
CONTINUUM_DB_USER=... CONTINUUM_DB_PASSWORD=... \
CONTINUUM_OIDC_ISSUER_URI=http://localhost:8180/realms/banque-demo \
CONTINUUM_AUDITTRAIL_HMAC_KEY=... \
mvn -o spring-boot:run
```

## Configuration

Only technical settings live in properties and environment variables; business settings live in the
database, per tenant. Secrets come from a vault and are never committed.

| Variable | Purpose |
| --- | --- |
| `CONTINUUM_DB_URL`, `CONTINUUM_DB_USER`, `CONTINUUM_DB_PASSWORD` | Database connection |
| `CONTINUUM_DB_DIALECT` | `postgresql` or `oracle` |
| `CONTINUUM_OIDC_ISSUER_URI` | Keycloak realm issuer |
| `CONTINUUM_AUDITTRAIL_HMAC_KEY` | Audit-trail signing key (required) |
| `CONTINUUM_SURFACE` | `all`, `app`, `admin` or `worker` |
| `CONTINUUM_CORS_ALLOWED_ORIGINS` | Comma-separated front-end origins (set explicitly in production) |
| `CONTINUUM_DIRECTORY_SYNC_ENABLED` | Scheduled directory sync from Keycloak user storage (off by default) |

Details are in `src/main/resources/application.yml`.

## Security model

Two filter chains: `/api/admin/**` (audience `continuum-admin`, `acr >= 2`) and `/api/app/**` (audience
`continuum-app`), plus a default chain for actuator and public endpoints. Method-level checks of the form
`@PreAuthorize("@authz.can(...)")` sit on top.

---

**Built by Hassan Lahlami**, Java / Spring Boot backend and FinTech API integration engineer. Open to
freelance work on banking integrations, payment APIs and secure Spring backends:
[Upwork profile](https://www.upwork.com/freelancers/~015cce5800215d394a).

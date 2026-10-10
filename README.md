# springboot-liquibase-poc

A proof of concept for managing an Azure SQL schema with Liquibase from a Spring Boot 4
application — where migrations run as a deliberate pipeline step rather than a startup
side effect.

The PoC exists to answer three questions:

1. Can the schema be versioned, previewed, applied, tagged and rolled back from the build?
2. Can a pull request validate the changelog with **no database and no credentials**?
3. Does the application refuse to start when a migration has been missed, instead of
   corrupting data?

All three are demonstrated here and covered by tests.

## Tech stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.0 |
| Database | Azure SQL / SQL Server 2022 (`mssql-jdbc`) |
| Migrations | Liquibase (XML changelogs, `liquibase-maven-plugin`) |
| Persistence | Spring Data JPA, Hibernate with `ddl-auto=validate` |
| Tests | JUnit 5, Testcontainers (`mssqlserver`) |
| Build | Maven (wrapper included), Lombok |

## Layout

```
pom.xml                     Two deliberately separate uses of liquibase-maven-plugin (see below)
docker-compose.yaml         Local SQL Server + one-shot database creation
scripts/migrate.sh          Preview -> apply -> tag, in one command
LIQUIBASE.md                Full command reference: update, rollback, inspect, troubleshoot
HELP.md                     Generated Spring Initializr links

src/main/java/com/example/liquibasepoc/
  SpringbootLiquibasePocApplication.java
  customer/Customer.java              Entity mirroring the migrated table
  customer/CustomerRepository.java    JpaRepository + findByEmail

src/main/resources/
  application.yaml
  db/changelog/db.changelog-master.xml
  db/changelog/changes/2026-08-01-POC-1-create-customer-table.xml
  db/changelog/changes/2026-08-10-POC-2-add-phone-number.xml

src/test/java/com/example/liquibasepoc/
  LiquibaseMigrationTests.java        Real changelog against a real SQL Server
  TestcontainersConfiguration.java    Throwaway container via @ServiceConnection
```

## The central design decision

`spring.liquibase.enabled` is **false** by default. The application never migrates on
startup. Instead:

| Stage | Command | Database |
|---|---|---|
| CI, every PR | `./mvnw verify` | none — offline |
| CD, before app deploy | `./scripts/migrate.sh <tag>` | that environment's |
| App startup | *(nothing)* | migrations disabled |

With `spring.jpa.hibernate.ddl-auto=validate`, a missed migration fails the application
at boot rather than letting it run against a schema it does not match. Migrate first,
then start.

### Why the POM configures the plugin twice

`liquibase-maven-plugin` is used for two different jobs, and the split matters:

- **CI** — a `validate-changelog` execution bound to the `validate` phase, pinned to an
  `offline:mssql` URL. It checks structure only: XSD conformance, unknown change types,
  duplicate changeset ids, unresolvable `<include>` paths. No server, no secrets,
  identical in every environment.
- **CD** — goals invoked directly with `-Dliquibase.url=...`, connecting to the real
  database.

The offline URL is scoped to the *execution*, not the plugin. Had it been declared at
plugin level, Maven's explicit configuration would win and `-Dliquibase.url` would be
**silently ignored**.

## Changelog conventions

- Files are named `<date>-<ticket>-<description>.xml` so concurrent branches don't
  collide on a sequence number.
- Changesets are **immutable once deployed** — identity is `(id, author, filename)` and
  content is checksummed. Fix forward with a new changeset; never edit a shipped one or
  rename its file.
- Columns are `nvarchar`, not `varchar`: `mssql-jdbc` sends string parameters as Unicode
  by default, so a `varchar` column forces an implicit conversion that turns index seeks
  into scans.
- Additive changes stay backward compatible — `POC-2` adds `phone_number` as nullable so
  the currently deployed app, which knows nothing about it, keeps inserting successfully.

## Running locally

Requires JDK 21 and Docker.

```bash
# 1. Start SQL Server and create the database.
#    Liquibase never issues CREATE DATABASE, so the compose file does it in a one-shot
#    container. The image is linux/amd64 only — on Apple Silicon it runs emulated.
docker compose up -d

# 2. Apply the changelog and tag the result.
./scripts/migrate.sh v1.0

# 3. Start the app (it validates the schema; it does not migrate).
./mvnw spring-boot:run
```

Local defaults are baked into `application.yaml` and `scripts/migrate.sh`. Override per
environment with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — and set
`trustServerCertificate=false` against a real Azure SQL instance.

To tear down, including the data volume:

```bash
docker compose down -v
```

## Tests

```bash
./mvnw verify
```

This does two things. The `validate` phase checks the changelog offline, needing no
database. Then `LiquibaseMigrationTests` runs the **real** changelog against a real
SQL Server in a Testcontainer, with `spring.liquibase.enabled=true`, asserting that:

- every changeset is recorded in `DATABASECHANGELOG`, in order;
- `customer` has exactly `id`, `first_name`, `email`, `phone_number`;
- an entity round-trips through the migrated schema with its IDENTITY id populated;
- `phone_number` is genuinely nullable, so pre-`POC-2` code paths still insert.

Context startup alone proves a good deal: Liquibase applied every changeset and
`ddl-auto=validate` then confirmed the entities match the result.

## Rollback

See [LIQUIBASE.md](LIQUIBASE.md) for the full reference. Three things worth knowing up
front:

- **Preview first, always.** Every rollback goal has a `*SQL` twin that generates the SQL
  without executing it.
- **`rollbackTag=X` undoes everything applied *after* X — not X itself.** Because tags are
  stamped *after* a successful apply, a tag marks the **end** of a release. To undo
  release 2, roll back to release 1's tag.
- **Rollback restores schema, not data.** Reversing a `dropColumn` gives back an *empty*
  column. The command succeeds; the data is gone.

Rollback is never automatic — Spring Boot only ever runs `update`, so deploying a previous
build rolls nothing back.

## Further reading

- [LIQUIBASE.md](LIQUIBASE.md) — command reference and troubleshooting
  (changelog lock, checksum mismatch)
- [HELP.md](HELP.md) — Spring Initializr reference links

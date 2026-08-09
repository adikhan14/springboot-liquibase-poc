# Liquibase commands

All commands run through the `liquibase-maven-plugin` declared in `pom.xml`.

## Connection

Every goal below needs a target database. Export these once per shell:

```bash
export LB_URL="jdbc:sqlserver://localhost:1433;database=liquibase_poc;encrypt=true;trustServerCertificate=true"
export LB_USER="sa"
export LB_PASS='L0cal!DevPassw0rd'

# An ARRAY, not a string. Expanded below as "${LB[@]}" so each -D stays a separate
# argument. A plain string collapses into ONE argument when quoted, and the username
# ends up inside the url value:
#   "...trustServerCertificate=true -Dliquibase.username=sa"  -> invalid boolean
LB=(-Dliquibase.url="$LB_URL"
    -Dliquibase.username="$LB_USER"
    -Dliquibase.password="$LB_PASS")
```

Local values shown. Against Azure SQL use that environment's own values and set
`trustServerCertificate=false`.

> These `-D` properties work because the offline url in `pom.xml` is scoped to the
> `validate-changelog` execution only. If it were declared at plugin level, Maven's
> explicit configuration would win and `-Dliquibase.url` would be **silently ignored**.

---

## 1. Validate — no database required

```bash
./mvnw verify
```

Runs automatically at the `validate` phase against a synthetic `offline:mssql`
connection. Checks XSD conformance, unknown change types, duplicate changeset ids
within a file, and unresolvable `<include>` paths. Needs no server and no credentials,
so it is safe in CI where the real url is not knowable.

It does **not** verify checksums against a real `DATABASECHANGELOG` — that is
per-environment, and `update` performs that check itself before applying anything.

## 2. Preview — see the SQL without running it

```bash
./mvnw liquibase:updateSQL "${LB[@]}"
cat target/liquibase/migrate.sql
```

Do this before every production apply.

## 3. Apply

```bash
./mvnw liquibase:update "${LB[@]}"
```

Applies every changeset in the changelog not already in `DATABASECHANGELOG`.

## 4. Tag

```bash
./mvnw liquibase:tag "${LB[@]}" -Dliquibase.tag=v1.0
```

Stamps the `TAG` column on the newest `DATABASECHANGELOG` row.

## Steps 2–4 in one go

```bash
./scripts/migrate.sh v1.0

# against another environment
DB_URL=... DB_USERNAME=... DB_PASSWORD=... ./scripts/migrate.sh v2.0
```

---

## Rollback

**Preview first. Always.** Every rollback command has a `*SQL` twin that generates the
SQL without executing it.

### Read this before using tags

`rollback --rollbackTag=X` undoes everything applied **after** X. It does not undo X.

Because we tag *after* applying, a tag marks the **end** of that release:

| Goal | Command |
|---|---|
| Undo release 2 | `-Dliquibase.rollbackTag=v1.0` ← the *previous* release's tag |
| Undo release 1 (the first) | no earlier tag exists — use `rollbackCount` |

Verified on this project: with only `POC-1` applied and tagged `v1.0`,
`-Dliquibase.rollbackTag=v1.0` generates **no SQL at all**, because nothing came after
it. `-Dliquibase.rollbackCount=1` generates `DROP TABLE customer`.

### By tag — the normal case

```bash
./mvnw liquibase:rollbackSQL "${LB[@]}" -Dliquibase.rollbackTag=v1.0    # preview
./mvnw liquibase:rollback    "${LB[@]}" -Dliquibase.rollbackTag=v1.0    # execute
```

### By count

```bash
./mvnw liquibase:rollbackSQL "${LB[@]}" -Dliquibase.rollbackCount=1
./mvnw liquibase:rollback    "${LB[@]}" -Dliquibase.rollbackCount=1
```

Undoes the last N changesets. Fragile — one logical change is often several changesets,
so count them in `DATABASECHANGELOG` first rather than guessing.

### By date

```bash
./mvnw liquibase:rollback "${LB[@]}" -Dliquibase.rollbackDate=2026-08-09
```

Undoes everything applied after that timestamp. Note it compares against `DATEEXECUTED`,
written in the **database server's** timezone — not your pipeline's.

### What rollback cannot do

- **It restores schema, not data.** Rolling back a `dropColumn` gives back an *empty*
  column. The command succeeds; the data is still gone.
- **It needs the changelog that made the change.** Rollback logic lives in the
  changeset, so run it from the version you are leaving, before deploying the old one.
- **It is never automatic.** Spring Boot only ever runs `update`. Deploying a previous
  build rolls nothing back.

---

## Inspect

```bash
./mvnw liquibase:status "${LB[@]}"                 # what is pending
./mvnw liquibase:history "${LB[@]}"                # what has been applied
```

Or query directly:

```sql
SELECT ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, TAG
FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED;
```

---

## Troubleshooting

### "Waiting for changelog lock"

A previous run was killed mid-update and left the lock set. **Confirm nothing is still
running**, then:

```bash
./mvnw liquibase:releaseLocks "${LB[@]}"
```

### Checksum mismatch

Someone edited a changeset that had already been applied. Changesets are **immutable
once deployed** — their identity is `(id, author, filename)` and their content is
checksummed. Fix forward with a new changeset rather than editing the old one, and
never rename a changelog file after it has shipped.

---

## Where this runs

| Stage | Command | Database |
|---|---|---|
| CI, every PR | `./mvnw verify` | none — offline |
| CD, before app deploy | `./scripts/migrate.sh <tag>` | that environment's |
| App startup | *(nothing)* | `spring.liquibase.enabled=false` |

`spring.jpa.hibernate.ddl-auto=validate` means the app refuses to start if the schema
does not match the entities — so a missed migration fails the app rather than corrupting
data. Migrate first, then start.

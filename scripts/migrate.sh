#!/usr/bin/env bash
#
# Apply pending Liquibase changesets, then tag the result.
#
#   ./scripts/migrate.sh v1.0
#
# Local defaults point at the docker-compose SQL Server. In CD, override with the
# environment's own values:
#
#   DB_URL=... DB_USERNAME=... DB_PASSWORD=... ./scripts/migrate.sh v2.0
#
# Run this BEFORE starting the app: spring.liquibase.enabled is false, so the app
# does not migrate on startup - it only validates that the schema matches the entities.
#
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    echo "usage: $0 <tag>      e.g. $0 v1.0" >&2
    exit 1
fi

DB_URL="${DB_URL:-jdbc:sqlserver://localhost:1433;database=liquibase_poc;encrypt=true;trustServerCertificate=true}"
DB_USERNAME="${DB_USERNAME:-sa}"
DB_PASSWORD="${DB_PASSWORD:-L0cal!DevPassw0rd}"

# The offline url in pom.xml is scoped to the validate execution only, so these
# -D properties do take effect when the goals are invoked directly.
LB=(-Dliquibase.url="$DB_URL"
    -Dliquibase.username="$DB_USERNAME"
    -Dliquibase.password="$DB_PASSWORD")

echo "==> Target: $DB_URL"

# Dry run first: prints the SQL to target/liquibase/migrate.sql, changes nothing.
echo "==> Previewing pending changes"
./mvnw -q -B liquibase:updateSQL "${LB[@]}"
grep -vE '^--|^$|^GO$' target/liquibase/migrate.sql | grep -vi DATABASECHANGELOG || true

echo "==> Applying"
./mvnw -B liquibase:update "${LB[@]}"

# Tag AFTER a successful update, so the tag marks the end of this release.
# To undo this release later, roll back to the PREVIOUS release's tag:
#     ./mvnw liquibase:rollback -Dliquibase.rollbackTag=<previous> ...
echo "==> Tagging as '$TAG'"
./mvnw -B liquibase:tag "${LB[@]}" -Dliquibase.tag="$TAG"

echo "==> Done. Schema is at '$TAG'."

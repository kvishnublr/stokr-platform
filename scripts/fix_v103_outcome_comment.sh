#!/usr/bin/env bash
set -euo pipefail
docker exec -i stokr-postgres psql -U postgres -d stokr_platform <<'SQL'
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS outcome_comment VARCHAR(500);
INSERT INTO flyway_schema_history (
  installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success
)
SELECT
  COALESCE((SELECT MAX(installed_rank) FROM flyway_schema_history), 0) + 1,
  '103',
  'add strategy signals outcome comment',
  'SQL',
  'V103__add_strategy_signals_outcome_comment.sql',
  NULL,
  'manual',
  NOW(),
  0,
  true
WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '103');
SQL
echo "V103 applied"
docker compose -f /opt/stokr/stokr-platform/docker-compose.yml --profile app restart api
sleep 90
docker inspect -f '{{.State.Health.Status}}' stokr-api
curl -fsS -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8080/actuator/health

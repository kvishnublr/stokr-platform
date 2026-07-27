#!/bin/bash
# Stokr Lite â€” Database Restore
# Usage: ./restore-db.sh [backup-file]
# If no file specified, uses the latest backup.

set -euo pipefail

DB_NAME="stokr_lite"
DB_USER="postgres"
DB_HOST="localhost"
DB_PORT="5432"
BACKUP_DIR="/opt/stokr/backups"

export PGPASSWORD="`$POSTGRES_PASSWORD"

if [ -n "${1:-}" ]; then
  BACKUP_FILE="$1"
else
  BACKUP_FILE=$(ls -t "$BACKUP_DIR"/stokr_lite_*.sql.gz 2>/dev/null | head -1)
fi

if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "ERROR: No backup file found."
  echo "Available backups:"
  ls -lh "$BACKUP_DIR"/stokr_lite_*.sql.gz 2>/dev/null || echo "  (none)"
  exit 1
fi

echo "=== Stokr Lite Database Restore ==="
echo "Backup file: $BACKUP_FILE"
echo "Size: $(du -h "$BACKUP_FILE" | cut -f1)"
echo "Database: $DB_NAME @ $DB_HOST:$DB_PORT"
echo ""
echo "This will DROP and recreate the stokr_lite database."
read -p "Continue? (yes/no): " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
  echo "Aborted."
  exit 1
fi

echo "[$(date)] Stopping stokr-lite service..."
systemctl stop stokr-lite 2>/dev/null || true

echo "[$(date)] Dropping and recreating database..."
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -c \
  "DROP DATABASE IF EXISTS $DB_NAME; CREATE DATABASE $DB_NAME;"

echo "[$(date)] Restoring from $BACKUP_FILE..."
gunzip -c "$BACKUP_FILE" | psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -q

echo "[$(date)] Starting stokr-lite service..."
systemctl start stokr-lite

echo "[$(date)] Restore complete."


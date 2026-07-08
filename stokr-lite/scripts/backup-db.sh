#!/bin/bash
# Stokr Lite — Automated Database Backup
# Runs daily via cron. Keeps 7 rolling backups.

set -euo pipefail

DB_NAME="stokr_lite"
DB_USER="postgres"
DB_HOST="localhost"
DB_PORT="5432"
BACKUP_DIR="/opt/stokr/backups"
KEEP_DAYS=7
DATE=$(date +%Y-%m-%d_%H%M)
BACKUP_FILE="${BACKUP_DIR}/stokr_lite_${DATE}.sql.gz"
LOG_FILE="${BACKUP_DIR}/backup.log"

export PGPASSWORD="stokr2026"

mkdir -p "$BACKUP_DIR"

echo "[$(date)] Starting backup..." >> "$LOG_FILE"

# Dump and compress
pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  --no-owner --no-privileges --clean --if-exists \
  | gzip > "$BACKUP_FILE"

SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "[$(date)] Backup complete: $BACKUP_FILE ($SIZE)" >> "$LOG_FILE"

# Rotate old backups
DELETED=$(find "$BACKUP_DIR" -name "stokr_lite_*.sql.gz" -mtime +${KEEP_DAYS} -delete -print | wc -l)
if [ "$DELETED" -gt 0 ]; then
  echo "[$(date)] Rotated $DELETED old backup(s)" >> "$LOG_FILE"
fi

# Summary
TOTAL=$(find "$BACKUP_DIR" -name "stokr_lite_*.sql.gz" | wc -l)
TOTAL_SIZE=$(du -sh "$BACKUP_DIR" | cut -f1)
echo "[$(date)] Backups on disk: $TOTAL ($TOTAL_SIZE)" >> "$LOG_FILE"

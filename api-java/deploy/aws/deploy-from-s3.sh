#!/usr/bin/env bash
# On-box (EC2) deploy: download the new JAR from S3, swap it in atomically, restart the
# systemd service, health-check, and roll back on failure. Mirrors Smart Gurukul.
# Invoked by SSM with S3_BUCKET and S3_JAR_KEY in the environment.
set -euo pipefail

: "${S3_BUCKET:?}" "${S3_JAR_KEY:?}"

APP_DIR=/opt/chhaon
JAR="$APP_DIR/chhaon-backend.jar"
NEW="$JAR.new"
PREV="$JAR.previous"
SERVICE=chhaon-backend
LOCK="$JAR.lock"

exec 9>"$LOCK"
flock -n 9 || { echo "DEPLOY FAILED: another deploy holds the lock"; exit 2; }

echo "Downloading s3://$S3_BUCKET/$S3_JAR_KEY ..."
aws s3 cp "s3://$S3_BUCKET/$S3_JAR_KEY" "$NEW" --region "${AWS_REGION:-ap-southeast-2}"

[ -f "$JAR" ] && cp -f "$JAR" "$PREV" || true
mv -f "$NEW" "$JAR"

echo "Restarting $SERVICE ..."
systemctl restart "$SERVICE"

echo "Health-checking ..."
for _ in $(seq 1 30); do
  if curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
    echo "DEPLOY OK"
    exit 0
  fi
  sleep 5
done

echo "DEPLOY FAILED: health check timed out — rolling back"
if [ -f "$PREV" ]; then
  mv -f "$PREV" "$JAR"
  systemctl restart "$SERVICE"
fi
journalctl -u "$SERVICE" --no-pager -n 50 | sed 's/\(password\|secret\|token\)=[^ ]*/\1=REDACTED/gi' || true
exit 3

#!/usr/bin/env bash
# CI-side: trigger a deploy on the EC2 box via AWS SSM Run Command.
# Mirrors Smart Gurukul: write runtime secrets into /etc/chhaon/backend.env (key-by-key, never
# wiping an unset one), then run the on-box pull/swap/restart/health/rollback script.
#
# Requires in the environment (from the workflow): AWS CLI configured, S3_BUCKET, S3_JAR_KEY,
# EC2_INSTANCE_ID, and the runtime secret vars. Inert until those AWS resources exist.
set -euo pipefail

: "${EC2_INSTANCE_ID:?}" "${S3_BUCKET:?}" "${S3_JAR_KEY:?}"

# Upload the current on-box script so the box always runs the latest version.
aws s3 cp "deploy/aws/deploy-from-s3.sh" "s3://${S3_BUCKET}/deploy/deploy-from-s3.sh"

# Managed runtime keys. A key is only rewritten when its value is non-empty, so a missing
# secret leaves the existing on-box value untouched.
MANAGED_KEYS=(
  SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
  APP_JWT_SECRET ANTHROPIC_API_KEY
  WHATSAPP_TOKEN WHATSAPP_PHONE_NUMBER_ID WHATSAPP_VERIFY_TOKEN WHATSAPP_APP_SECRET
)

# Build base64-wrapped KEY=VALUE pairs for safe transport through the SSM shell.
ENV_B64_LINES=""
for k in "${MANAGED_KEYS[@]}"; do
  v="${!k:-}"
  [ -z "$v" ] && continue
  ENV_B64_LINES+="$(printf '%s=%s' "$k" "$v" | base64)\n"
done

read -r -d '' REMOTE <<REMOTE_SCRIPT || true
set -euo pipefail
sudo install -d -m 750 /etc/chhaon /opt/chhaon
ENV_FILE=/etc/chhaon/backend.env
sudo touch "\$ENV_FILE"; sudo chmod 640 "\$ENV_FILE"
printf '%b' "${ENV_B64_LINES}" | while IFS= read -r line; do
  [ -z "\$line" ] && continue
  decoded="\$(printf '%s' "\$line" | base64 --decode)"
  key="\${decoded%%=*}"
  sudo sed -i "/^\${key}=/d" "\$ENV_FILE"
  echo "\$decoded" | sudo tee -a "\$ENV_FILE" >/dev/null
done
# Install + run the latest deploy script.
aws s3 cp "s3://${S3_BUCKET}/deploy/deploy-from-s3.sh" /opt/chhaon/deploy-from-s3.sh
sudo install -m 750 /opt/chhaon/deploy-from-s3.sh /opt/chhaon/deploy-from-s3.sh
sudo S3_BUCKET="${S3_BUCKET}" S3_JAR_KEY="${S3_JAR_KEY}" bash /opt/chhaon/deploy-from-s3.sh
REMOTE_SCRIPT

CMD_ID=$(aws ssm send-command \
  --instance-ids "${EC2_INSTANCE_ID}" \
  --document-name "AWS-RunShellScript" \
  --comment "Chhaon backend deploy ${GITHUB_SHA:-manual}" \
  --parameters "commands=[$(printf '%s' "$REMOTE" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')]" \
  --query "Command.CommandId" --output text)

echo "SSM command: $CMD_ID"
# Poll for completion.
for _ in $(seq 1 60); do
  STATUS=$(aws ssm get-command-invocation --command-id "$CMD_ID" \
            --instance-id "${EC2_INSTANCE_ID}" --query "Status" --output text 2>/dev/null || echo "Pending")
  echo "status: $STATUS"
  case "$STATUS" in
    Success) exit 0 ;;
    Failed|Cancelled|TimedOut) aws ssm get-command-invocation --command-id "$CMD_ID" \
        --instance-id "${EC2_INSTANCE_ID}" --query "StandardErrorContent" --output text; exit 1 ;;
  esac
  sleep 5
done
echo "SSM command did not finish in time"; exit 1

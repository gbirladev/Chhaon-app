#!/usr/bin/env bash
# CI-side: trigger a deploy on the EC2 box via AWS SSM Run Command.
# Writes runtime secrets into /etc/chhaon/backend.env (key-by-key; a missing/empty secret
# leaves the existing on-box value untouched), then runs the on-box pull/swap/restart/health/
# rollback script. The whole remote script is base64-wrapped into a single SSM command token
# (passing a multi-line script via the --parameters shorthand mangles newlines).
set -euo pipefail

: "${EC2_INSTANCE_ID:?}" "${S3_BUCKET:?}" "${S3_JAR_KEY:?}" "${AWS_REGION:=ap-southeast-2}"

# Upload the latest on-box deploy script so the box always runs the current version.
aws s3 cp "deploy/aws/deploy-from-s3.sh" "s3://${S3_BUCKET}/deploy/deploy-from-s3.sh"

MANAGED_KEYS=(
  SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
  APP_JWT_SECRET SUPABASE_JWT_SECRET ANTHROPIC_API_KEY
  WHATSAPP_TOKEN WHATSAPP_PHONE_NUMBER_ID WHATSAPP_VERIFY_TOKEN WHATSAPP_APP_SECRET
  WHATSAPP_GATEWAY_URL WHATSAPP_GATEWAY_TOKEN WHATSAPP_SENDER_SESSION_NAME WHATSAPP_SENDER_PHONE
  WHATSAPP_WEBHOOK_SECRET SUPABASE_SEND_SMS_HOOK_SECRET
  SUPABASE_URL SUPABASE_SERVICE_ROLE_KEY
  CHHAON_SCHEDULER_ENABLED CHHAON_SCHEDULER_ONLY_RECIPIENT
  WHATSAPP_POLLS_ENABLED BETTERSTACK_HEARTBEAT_URL
  OTP_REVIEW_PHONE OTP_REVIEW_CODE
)

# Build the remote script. Each managed secret is embedded as a base64 KEY=VALUE line so the
# values never need shell-escaping; empty ones are skipped (keep existing on-box value).
ENV_LINES=""
for k in "${MANAGED_KEYS[@]}"; do
  v="${!k:-}"
  [ -z "$v" ] && continue
  ENV_LINES+="$(printf '%s=%s' "$k" "$v" | base64 | tr -d '\n')"$'\n'
done

REMOTE=$(cat <<EOF
#!/bin/bash
set -euo pipefail
install -d -m 750 /etc/chhaon /opt/chhaon
ENV_FILE=/etc/chhaon/backend.env
touch "\$ENV_FILE"; chmod 640 "\$ENV_FILE"
while IFS= read -r line; do
  [ -z "\$line" ] && continue
  decoded="\$(printf '%s' "\$line" | base64 --decode)"
  key="\${decoded%%=*}"
  sed -i "/^\${key}=/d" "\$ENV_FILE"
  printf '%s\n' "\$decoded" >> "\$ENV_FILE"
done <<'ENVB64'
${ENV_LINES}ENVB64
aws s3 cp "s3://${S3_BUCKET}/deploy/deploy-from-s3.sh" /opt/chhaon/deploy-from-s3.sh --region ${AWS_REGION}
chmod 750 /opt/chhaon/deploy-from-s3.sh
S3_BUCKET="${S3_BUCKET}" S3_JAR_KEY="${S3_JAR_KEY}" AWS_REGION="${AWS_REGION}" bash /opt/chhaon/deploy-from-s3.sh
EOF
)

B64=$(printf '%s' "$REMOTE" | base64 | tr -d '\n')
CMD_ID=$(aws ssm send-command \
  --instance-ids "${EC2_INSTANCE_ID}" \
  --document-name "AWS-RunShellScript" \
  --comment "Chhaon deploy ${GITHUB_SHA:-manual}" \
  --timeout-seconds 600 \
  --parameters commands="[\"echo ${B64} | base64 --decode | bash\"]" \
  --query "Command.CommandId" --output text)

echo "SSM command: $CMD_ID"
for _ in $(seq 1 60); do
  STATUS=$(aws ssm get-command-invocation --command-id "$CMD_ID" \
            --instance-id "${EC2_INSTANCE_ID}" --query "Status" --output text 2>/dev/null || echo "Pending")
  echo "status: $STATUS"
  case "$STATUS" in
    Success) aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "${EC2_INSTANCE_ID}" \
               --query "StandardOutputContent" --output text | tail -5; exit 0 ;;
    Failed|Cancelled|TimedOut)
      aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "${EC2_INSTANCE_ID}" \
        --query "StandardErrorContent" --output text; exit 1 ;;
  esac
  sleep 5
done
echo "SSM command did not finish in time"; exit 1

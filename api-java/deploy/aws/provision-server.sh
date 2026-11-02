#!/usr/bin/env bash
# One-time EC2 provisioning (Amazon Linux 2023, t3.small, ap-south-1). Idempotent.
# Installs the JRE, nginx + certbot, creates dirs + the systemd unit. No IaC by design
# (mirrors Smart Gurukul). Run as a user with sudo on a fresh box.
set -euo pipefail

sudo dnf update -y
sudo dnf install -y java-21-amazon-corretto-headless nginx certbot python3-certbot-nginx curl

sudo install -d -m 750 /opt/chhaon /etc/chhaon
[ -f /etc/chhaon/backend.env ] || { sudo touch /etc/chhaon/backend.env; sudo chmod 640 /etc/chhaon/backend.env; }

# systemd unit (copy this repo's unit file to the box first, or paste it here).
sudo cp "$(dirname "$0")/chhaon-backend.service" /etc/systemd/system/chhaon-backend.service
sudo systemctl daemon-reload
sudo systemctl enable chhaon-backend

# nginx
sudo cp "$(dirname "$0")/nginx-chhaon.conf" /etc/nginx/conf.d/chhaon.conf
sudo systemctl enable --now nginx
sudo nginx -t && sudo systemctl reload nginx

echo "Provisioned. Next: set /etc/chhaon/backend.env, then run certbot --nginx -d api.chhaon.site"
echo "Ensure this instance has an IAM role allowing s3:GetObject on the deploy bucket and SSM."
echo
echo "REQUIRED — stable IP: allocate an Elastic IP and associate it with THIS instance so the"
echo "public IP never changes on stop/start/replace. Then point the api.chhaon.site DNS A record"
echo "at the Elastic IP (set once). One-time, from a machine with the AWS CLI + permissions:"
echo "  EIP_ALLOC=\$(aws ec2 allocate-address --domain vpc --region ap-south-1 --query AllocationId --output text)"
echo "  aws ec2 associate-address --region ap-south-1 --instance-id <THIS_INSTANCE_ID> --allocation-id \$EIP_ALLOC"
echo "(Deploys do NOT change the IP — they swap the JAR on the running box; the EIP covers stop/start.)"

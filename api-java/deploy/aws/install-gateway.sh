#!/usr/bin/env bash
# Install the WA-AKG WhatsApp gateway on the EC2 box (Amazon Linux 2023).
# The gateway source is expected at /opt/whatsapp-gateway (uploaded separately, e.g. from S3),
# and /opt/whatsapp-gateway/.env must be filled from whatsapp-gateway.env.example.
# Baileys speaks the WhatsApp Web protocol directly — no Chromium needed.
set -euo pipefail

# Node 22 (NodeSource).
if ! node --version 2>/dev/null | grep -q '^v22'; then
  curl -fsSL https://rpm.nodesource.com/setup_22.x | sudo bash -
  sudo dnf install -y nodejs
fi

cd /opt/whatsapp-gateway
npm ci
npx prisma generate
# Create the gateway's tables in the wa_akg schema (DATABASE_URL from .env).
set -a; . ./.env; set +a
npx prisma db push

# systemd service.
sudo cp "$(dirname "$0")/chhaon-wa.service" /etc/systemd/system/chhaon-wa.service 2>/dev/null || true
sudo systemctl daemon-reload
sudo systemctl enable --now chhaon-wa

# nginx for the dashboard/QR (optional but recommended for the one-time QR scan).
sudo cp "$(dirname "$0")/nginx-wa.conf" /etc/nginx/conf.d/wa.conf 2>/dev/null || true
sudo nginx -t && sudo systemctl reload nginx || true

echo "Gateway installed. Next: certbot --nginx -d wa.chhaon.site, open https://wa.chhaon.site,"
echo "create a session named 'Chhaon', scan the QR with the dedicated WhatsApp number, then mint a"
echo "wag_ API key and set WHATSAPP_GATEWAY_URL/TOKEN on the backend."

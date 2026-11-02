# Chhaon AWS infrastructure (live)

Provisioned 2026-11-02 in **ap-southeast-2 (Sydney)** — the free-plan account is region-locked
to Sydney, and the Supabase DB was moved to Sydney to co-locate. No Terraform; resources were
created with the AWS CLI (see `provision-server.sh` for the box bootstrap).

| Resource | Value |
|---|---|
| AWS account | `304485839057` |
| Region | `ap-southeast-2` |
| EC2 instance | `i-06c5527278a02b5df` (t3.small, Amazon Linux 2023, 16 GB gp3) |
| Elastic IP | `54.79.216.98` (allocation `eipalloc-0b5d6c093c430cfd5`) — stable across stop/start |
| S3 deploy bucket | `chhaon-deploys-304485839057` (jar key `chhaon-backend.jar`) |
| Instance role | `chhaon-ec2-role` (AmazonSSMManagedInstanceCore + s3:GetObject on the bucket) |
| Security group | `sg-06a3f6ea316b85a6f` (inbound 80, 443) |
| CI user | `chhaon-ci` (scoped: s3:PutObject + ssm:SendCommand to the instance) — its key is the GitHub `AWS_*` secrets |
| VPC / subnet | `vpc-083517f70c4c7ef1b` / `subnet-060e515d61d3bfc52` (default VPC) |

The box runs the Spring Boot JAR as a systemd service `chhaon-backend` behind nginx (:80 → :8080).
Runtime secrets live in `/etc/chhaon/backend.env`, written by the deploy pipeline from GitHub
Secrets. Deploys never change the Elastic IP (JAR swap on the running box via SSM).

## Status
- Backend is **live**: `http://54.79.216.98/actuator/health` → `{"status":"UP"}`.
- GitHub Actions `backend-deploy.yml` is wired and validated end-to-end (build → S3 → SSM → swap
  → health-check → rollback). Push to `main` touching `api-java/**` auto-deploys.

## TODO
- Point `api.chhaon.site` DNS (A record) at `54.79.216.98`, then run
  `sudo certbot --nginx -d api.chhaon.site` on the box for TLS, and update the nginx server_name +
  the `HEALTH_CHECK_URL` secret to `https://api.chhaon.site/actuator/health`.
- Rotate the `chhaon-admin-setup` access key that was used for provisioning.

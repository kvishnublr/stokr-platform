# Contabo auto-deploy setup

SSH to `173.249.55.84` accepts **public keys only** (password `Temp1234` is for Contabo panel / web console, not SSH).

## One-time server setup (Contabo web console)

```bash
cd /opt/stokr/stokr-platform
git fetch origin Release_v1 && git checkout Release_v1 && git pull origin Release_v1
bash scripts/contabo_bootstrap_autodeploy.sh
```

This will:
- Add the GitHub Actions deploy public key to `authorized_keys`
- Install a cron job that polls `Release_v1` every 2 minutes and runs `./deploy.sh` (auto UI/API detection)
- Run an immediate full deploy

## GitHub Actions secrets (repo Settings → Secrets → Actions)

| Secret | Value |
|--------|--------|
| `DEPLOY_HOST` | `173.249.55.84` |
| `DEPLOY_USER` | `root` |
| `DEPLOY_PATH` | `/opt/stokr/stokr-platform` |
| `DEPLOY_SSH_KEY` | Private key printed by `scripts/contabo_bootstrap_autodeploy.sh` |

Generate a fresh pair on the server if needed:

```bash
ssh-keygen -t ed25519 -f /root/.ssh/github_actions_deploy -N "" -C "github-actions-stokr"
cat /root/.ssh/github_actions_deploy.pub >> /root/.ssh/authorized_keys
cat /root/.ssh/github_actions_deploy   # paste into DEPLOY_SSH_KEY secret
```

Paste the full private key into `DEPLOY_SSH_KEY`, including the `BEGIN/END`
lines. The workflow accepts either normal multiline keys or keys pasted with
literal `\n` line separators, but the private key must match a public key in
`/root/.ssh/authorized_keys` on the Contabo server.

If GitHub Actions fails with `Permission denied (publickey)`:

**Root cause:** `DEPLOY_SSH_KEY` is valid, but the matching public key is not in
`/root/.ssh/authorized_keys` on the host. The Postgres “emergency” step cannot fix
this when Postgres runs in Docker (it cannot write `/root/.ssh` on the host).

**Fix (one time, Contabo web/serial console as root):**

```bash
curl -fsSL https://raw.githubusercontent.com/kvishnublr/stokr-platform/Release_v1/scripts/contabo_console_fix_ssh.sh | bash
```

This installs the deploy key, pulls `Release_v1`, and runs a full deploy.

Then re-run **Auto Deploy to Contabo** (Actions → Run workflow) or push to `Release_v1`.

Optional: run **Repair Deploy SSH Secret** workflow — it publishes the
`DEPLOY_SSH_KEY` public half into `deploy/contabo_github_deploy.pub` on GitHub.

If the repo already exists on the server:

```bash
bash /opt/stokr/stokr-platform/scripts/contabo_console_fix_ssh.sh
```

If the repo is missing, rerun bootstrap:

```bash
cd /opt/stokr/stokr-platform && bash scripts/contabo_bootstrap_autodeploy.sh
```

Then update `DEPLOY_SSH_KEY` with the private key it prints if it generated a new pair.

## How auto-deploy works

1. **GitHub Actions** — on every push to `Release_v1`, workflow runs `./deploy.sh api ui` over SSH.
2. **Server cron** — every 2 min, `scripts/contabo_poll_deploy.sh` fetches `Release_v1`; if HEAD changed, runs `./deploy.sh` which auto-detects UI vs API from changed files.

## Manual deploy

```bash
cd /opt/stokr/stokr-platform
bash scripts/server_deploy_release_v1.sh
```

## Logs

```bash
tail -f /var/log/stokr-deploy.log
docker logs stokr-api --tail 100
docker logs stokr-ui --tail 50
```

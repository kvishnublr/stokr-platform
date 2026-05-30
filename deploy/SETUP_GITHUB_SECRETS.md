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

1. Run **Repair Deploy SSH Secret** workflow (Actions tab) — it publishes the
   `DEPLOY_SSH_KEY` public half into `deploy/contabo_github_deploy.pub`.
2. On Contabo **web/serial console** as root (password SSH is disabled):

```bash
bash /opt/stokr/stokr-platform/scripts/contabo_console_fix_ssh.sh
```

Or paste the one-liner from that script if the repo is not pulled yet.

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

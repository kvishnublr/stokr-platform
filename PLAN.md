# Deploy Crystal Light UI to stokr.in

## Context

The Crystal Light Premium UI redesign has been completed locally with all 19 frontend files updated. These changes are currently uncommitted on the `Release_v6` branch. The user wants to:

1. Commit all UI changes to `Release_v6`
2. Deploy the application to the root domain `stokr.in` (instead of the raw IP `173.249.55.84`)
3. Use SSH to update the server configuration directly

The current nginx setup routes `prod.stokr.in` and `new.stokr.in` but does not have a server block for the root `stokr.in` domain.

## Files Modified (to commit)

All changes are in `stokr-lite/frontend/src/`:

- `components/Layout.jsx` - Light sidebar redesign
- `index.css` - Crystal Light theme system (card-crystal, table-crystal, input-crystal, animations)
- `pages/Login.jsx` - Split-panel login with animated orbs
- `pages/Register.jsx` - Matching register page
- `pages/Dashboard.jsx` - Stats cards with hover effects
- `pages/Strategies.jsx` - Strategy catalog cards
- `pages/Deployments.jsx` - Deployment cards
- `pages/Orders.jsx` - Order tracking tables
- `pages/Positions.jsx` - Position tracking
- `pages/Brokers.jsx` - Broker cards
- `pages/Settings.jsx` - Crystal form inputs
- `pages/admin/AdminDashboard.jsx` - Admin stats grid
- `pages/admin/AdminUniverseGroups.jsx` - Universe group cards
- `pages/admin/AdminStrategyMappings.jsx` - Clean table design
- `pages/admin/AdminStrategyConfigs.jsx` - Config cards
- `pages/admin/AdminBrokerHealth.jsx` - Status badges
- `pages/admin/AdminKillSwitch.jsx` - Status indicator
- `pages/admin/AdminDeployments.jsx` - Deployment table
- `pages/admin/AdminErrorLogs.jsx` - Error log table
- `pages/admin/AdminUsers.jsx` - User management table

## Plan

### Task 1: Commit and Push Changes

```bash
cd c:\Users\itsvi\Desktop\work_new\stokr-platform

# Stage all modified frontend files
git add stokr-lite/frontend/src/components/Layout.jsx
git add stokr-lite/frontend/src/index.css
git add stokr-lite/frontend/src/pages/*.jsx
git add stokr-lite/frontend/src/pages/admin/*.jsx

# Commit with descriptive message
git commit -m "ui: Crystal Light Premium theme redesign

- Complete light theme overhaul across all pages
- New CSS system: card-crystal, table-crystal, input-crystal
- Animated orbs, gradient headers, hover-lift effects
- Consistent badge styling with uppercase tracking
- Light sidebar replacing dark navy theme
- Refined animations: fadeInUp, floatOrb, subtleGlow"

# Push to Release_v6
git push origin Release_v6
```

### Task 2: SSH to Server and Pull Latest Code

```bash
ssh -i ~/.ssh/id_rsa_stokr root@173.249.55.84

cd /root/stokr-lite
git pull origin Release_v6
```

### Task 3: Update Nginx Config for stokr.in

Add a new server block to `/etc/nginx/conf.d/` (or wherever nginx-router.conf lives) to serve the stokr-lite app on the root domain `stokr.in`.

The new server block should:
- Listen on 80 and 443
- Have `server_name stokr.in www.stokr.in;`
- Proxy API calls to `localhost:8070`
- Serve frontend static files from `/usr/share/nginx/html` (or proxy to port 8082)
- Include SSL certificate config (reuse existing certs or create new)

Alternatively, add `stokr.in` to the existing `server_name` line in the main server block and update routing.

### Task 4: Update Deploy Scripts

Update these scripts to reference `stokr.in` instead of `173.249.55.84`:
- `stokr-lite/deploy.sh`
- `stokr-lite/redeploy.sh`
- `stokr-lite/final-deploy.sh`
- `stokr-lite/verify.sh`

### Task 5: Build and Deploy

```bash
cd /root/stokr-lite

# Build frontend
npm run build

# Copy dist to static resources
mkdir -p backend/src/main/resources/static
cp -r frontend/dist/* backend/src/main/resources/static/

# Build backend
cd backend
mvn package -DskipTests -q

# Restart application
pkill -f 'stokr-lite.*8070' || true
sleep 2

nohup java -jar target/stokr-lite-*.jar \
  --server.port=8070 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/stokr_lite \
  --spring.datasource.username=stokr \
  --spring.datasource.password=root123 \
  --jwt.secret=stokr-lite-production-secret-key-that-is-at-least-256-bits-long \
  > /root/stokr-lite/app.log 2>&1 &

# Restart nginx
docker restart stokr-lite-nginx
```

### Task 6: Verify Deployment

```bash
# Check backend health
curl -s http://localhost:8070/actuator/health

# Check frontend via nginx
curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/

# Verify domain is accessible
curl -s -o /dev/null -w '%{http_code}' https://stokr.in/
```

## Verification

- [ ] All 19 frontend files committed to Release_v6
- [ ] Code pushed to GitHub
- [ ] Server has latest code
- [ ] Nginx config updated for stokr.in
- [ ] Backend starts successfully on port 8070
- [ ] Frontend accessible via nginx on port 8082
- [ ] stokr.in domain resolves and serves the application
- [ ] SSL works on stokr.in (if cert is configured)

## Rollback Plan

If deployment fails:
1. Restore previous JAR from backup
2. Restore previous nginx config
3. Restart services with old version
4. Investigate logs at `/root/stokr-lite/app.log`

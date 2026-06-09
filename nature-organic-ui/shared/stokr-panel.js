(function (global) {
  'use strict';

  const CONFIG = {
    API_BASE: (global.location && global.location.hostname === '173.249.55.84')
      ? 'http://173.249.55.84:8080/api'
      : '/api',
    TIMEOUT: 20000,
    REFRESH_MS: 30000,
  };

  const fmt = {
    money(v) {
      if (v == null || v === '' || Number.isNaN(Number(v))) return '-';
      const n = Number(v);
      const abs = Math.abs(n);
      const sign = n < 0 ? '-' : '';
      if (abs >= 1e7) return sign + '\u20B9' + (abs / 1e7).toFixed(2) + ' Cr';
      if (abs >= 1e5) return sign + '\u20B9' + (abs / 1e5).toFixed(2) + ' L';
      if (abs >= 1e3) return sign + '\u20B9' + abs.toLocaleString('en-IN', { maximumFractionDigits: 0 });
      return sign + '\u20B9' + abs.toFixed(2);
    },
    num(v, d) {
      if (v == null || v === '') return '-';
      const n = Number(v);
      return Number.isFinite(n) ? n.toFixed(d || 0) : String(v);
    },
    pct(v) {
      if (v == null || v === '') return '-';
      const n = Number(v);
      return Number.isFinite(n) ? (n >= 0 ? '+' : '') + n.toFixed(2) + '%' : String(v);
    },
    time(v) {
      if (!v) return '-';
      try {
        return new Date(v).toLocaleString('en-IN', { hour12: false });
      } catch (_) {
        return String(v);
      }
    },
    text(v, fb) {
      return v == null || v === '' ? (fb || '-') : String(v);
    },
    date(v) {
      if (!v) return '-';
      try {
        return new Date(v).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
      } catch (_) { return String(v); }
    },
    yesno(v) { return v ? 'Yes' : 'No'; },
    statusBadge(s) {
      const m = { ENABLED: '#2d6a4f', ACTIVE: '#2d6a4f', FILLED: '#2d6a4f', LIVE: '#386395', PAPER: '#9caf88', REJECTED: '#c44', FAILED: '#c44', DISABLED: '#999', PENDING: '#d4a574' };
      const c = m[String(s).toUpperCase()] || '#386395';
      return '<span style="background:' + c + ';color:#fff;padding:3px 10px;border-radius:10px;font-size:11px;font-weight:700">' + s + '</span>';
    },
  };

  /* ---------- API Client ---------- */
  class StokrClient {
    constructor(role) {
      this.role = role;
      this.tokenKey = role === 'admin' ? 'stokr_admin_token' : 'stokr_trader_token';
      this.refreshKey = role === 'admin' ? 'stokr_admin_refresh' : 'stokr_trader_refresh';
    }

    getToken() {
      return localStorage.getItem(this.tokenKey) || localStorage.getItem('accessToken') || '';
    }

    setSession(data) {
      if (!data?.accessToken) return;
      localStorage.setItem(this.tokenKey, data.accessToken);
      if (data.refreshToken) localStorage.setItem(this.refreshKey, data.refreshToken);
      localStorage.setItem('accessToken', data.accessToken);
      if (data.refreshToken) localStorage.setItem('refreshToken', data.refreshToken);
    }

    clearSession() {
      localStorage.removeItem(this.tokenKey);
      localStorage.removeItem(this.refreshKey);
      if (this.role === 'trader') {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
      }
    }

    async request(path, options) {
      options = options || {};
      const url = path.startsWith('http') ? path : CONFIG.API_BASE + path;
      const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
      const token = this.getToken();
      if (token && !options.skipAuth) headers.Authorization = 'Bearer ' + token;
      const ctrl = new AbortController();
      const timer = setTimeout(function () { ctrl.abort(); }, CONFIG.TIMEOUT);
      const started = performance.now();
      try {
        const res = await fetch(url, Object.assign({}, options, { headers, signal: ctrl.signal }));
        clearTimeout(timer);
        const latency = Math.round(performance.now() - started);
        if (!res.ok) {
          const errText = await res.text().catch(function () { return ''; });
          throw new Error('HTTP ' + res.status + (errText ? ': ' + errText.slice(0, 160) : ''));
        }
        const ct = res.headers.get('content-type') || '';
        const json = ct.includes('json') ? await res.json() : null;
        return { json, latency, res };
      } catch (e) {
        clearTimeout(timer);
        throw e;
      }
    }

    async login(principal, password) {
      const { json } = await this.request('/auth/login', {
        method: 'POST', body: JSON.stringify({ principal, password }), skipAuth: true,
      });
      if (!json?.data?.accessToken) throw new Error(json?.message || 'Login failed');
      this.setSession(json.data);
      return json.data;
    }

    async get(path) { return this.request(path); }
    async post(path, body) { return this.request(path, { method: 'POST', body: JSON.stringify(body || {}) }); }
    async put(path, body) { return this.request(path, { method: 'PUT', body: JSON.stringify(body || {}) }); }
    async patch(path, body) { return this.request(path, { method: 'PATCH', body: JSON.stringify(body || {}) }); }
    async del(path) { return this.request(path, { method: 'DELETE' }); }
  }

  /* ---------- UI Helpers ---------- */
  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function table(headers, rows) {
    const head = headers.map(function (h) { return '<th>' + h + '</th>'; }).join('');
    const body = rows.length
      ? rows.map(function (cells) { return '<tr>' + cells.map(function (c) { return '<td>' + c + '</td>'; }).join('') + '</tr>'; }).join('')
      : '<tr><td colspan="' + headers.length + '" style="text-align:center;padding:30px;color:#999">No data</td></tr>';
    return '<table><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody></table>';
  }

  function metricsCard(title, rows) {
    var inner = rows.map(function (r) {
      return '<div class="metric"><span class="metric-label">' + r[0] + '</span><span class="metric-value">' + r[1] + '</span></div>';
    }).join('');
    return '<div class="card"><div class="card-title">' + title + '</div>' + inner + '</div>';
  }

  function grid(cardsHtml) {
    return '<div class="dashboard-grid">' + cardsHtml + '</div>';
  }

  /* ---------- Modal System ---------- */
  var modalCallbacks = {};

  function showModal(title, bodyHtml, onConfirm, confirmText, danger) {
    var overlay = document.createElement('div');
    overlay.id = 'stokrModalOverlay';
    overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:1000;display:flex;align-items:center;justify-content:center;animation:slideInUp 0.3s ease-out';
    var modal = document.createElement('div');
    modal.style.cssText = 'background:#fff;border-radius:16px;padding:28px;max-width:600px;width:90%;max-height:80vh;overflow-y:auto;box-shadow:0 20px 60px rgba(0,0,0,0.2);border:2px solid #d4a574';
    modal.innerHTML =
      '<div style="font-size:20px;font-weight:900;color:#2d6a4f;margin-bottom:16px;border-bottom:2px solid #d4a574;padding-bottom:12px">' + esc(title) + '</div>' +
      '<div style="margin-bottom:20px">' + bodyHtml + '</div>' +
      '<div style="display:flex;gap:12px;justify-content:flex-end">' +
      '<button id="stokrModalCancel" style="padding:10px 20px;border:2px solid #d4a574;border-radius:8px;background:#fff;color:#2d6a4f;font-weight:700;cursor:pointer">Cancel</button>' +
      (onConfirm
        ? '<button id="stokrModalConfirm" style="padding:10px 20px;border:none;border-radius:8px;background:' + (danger ? '#c44' : 'linear-gradient(135deg,#2d6a4f,#386395)') + ';color:#fff;font-weight:700;cursor:pointer">' + (confirmText || 'Confirm') + '</button>'
        : '') +
      '</div>';
    overlay.appendChild(modal);
    document.body.appendChild(overlay);
    overlay.addEventListener('click', function (e) { if (e.target === overlay) closeModal(); });
    if (onConfirm) {
      document.getElementById('stokrModalConfirm').onclick = function () {
        var result = onConfirm();
        if (result !== false) closeModal();
      };
    }
    document.getElementById('stokrModalCancel').onclick = closeModal;
  }

  function closeModal() {
    var el = document.getElementById('stokrModalOverlay');
    if (el) { el.remove(); }
  }

  function field(label, id, value, type, placeholder) {
    type = type || 'text';
    return '<div style="margin-bottom:12px"><label style="display:block;font-weight:700;font-size:12px;color:#2d6a4f;margin-bottom:4px">' + esc(label) + '</label>' +
      (type === 'select'
        ? '<select id="' + id + '" style="width:100%;padding:10px;border:2px solid #d4a574;border-radius:8px;font-size:14px">' + (value || '') + '</select>'
        : type === 'textarea'
          ? '<textarea id="' + id + '" style="width:100%;padding:10px;border:2px solid #d4a574;border-radius:8px;font-size:14px;min-height:80px">' + esc(value || '') + '</textarea>'
          : '<input id="' + id + '" type="' + type + '" value="' + esc(value || '') + '" placeholder="' + esc(placeholder || '') + '" style="width:100%;padding:10px;border:2px solid #d4a574;border-radius:8px;font-size:14px">') +
      '</div>';
  }

  function val(id) {
    var el = document.getElementById(id);
    return el ? el.value : '';
  }

  /* ---------- Toast ---------- */
  function toast(msg, type) {
    type = type || 'info';
    var div = document.createElement('div');
    var bg = type === 'success' ? '#2d6a4f' : type === 'error' ? '#c44' : '#386395';
    div.style.cssText = 'position:fixed;top:20px;right:20px;z-index:2000;background:' + bg + ';color:#fff;padding:14px 24px;border-radius:12px;font-weight:700;font-size:14px;box-shadow:0 8px 32px rgba(0,0,0,0.2);animation:slideInUp 0.3s ease-out;max-width:400px';
    div.textContent = msg;
    document.body.appendChild(div);
    setTimeout(function () { div.style.opacity = '0'; div.style.transition = 'opacity 0.5s'; setTimeout(function () { div.remove(); }, 500); }, 3500);
  }

  /* ---------- Data Fetching ---------- */
  async function safeAll(client, reqs) {
    const out = {};
    await Promise.all(reqs.map(async function (pair) {
      try {
        const { json } = await client.get(pair[1]);
        out[pair[0]] = json?.data;
      } catch (e) {
        out[pair[0]] = { __error: String(e.message || e) };
      }
    }));
    return out;
  }

  /* ---------- Panel App Factory ---------- */
  function createPanelApp(opts) {
    const client = new StokrClient(opts.role);
    let cache = {};
    let chartRef = null;
    let refreshTimer = null;
    let currentPage = 'dashboard';

    function setStatus(ok, latency, detail) {
      const bar = document.getElementById('stokrStatusBar');
      if (!bar) return;
      bar.innerHTML =
        '<div class="status-item"><span style="width:10px;height:10px;background:' + (ok ? '#2d6a4f' : '#d68a8a') + ';border-radius:50%"></span>' +
        (ok ? 'API Connected' : 'API Error') + (latency ? ' \u00B7 ' + latency + 'ms' : '') + '</div>' +
        (detail ? '<div class="status-item">' + esc(detail) + '</div>' : '');
    }

    function showLogin() {
      const area = document.getElementById('contentArea');
      area.innerHTML =
        '<div class="page-title">Sign in</div>' +
        '<div class="page-subtitle">Connect to the STOKR API (' + CONFIG.API_BASE + ')</div>' +
        '<div class="card" style="max-width:480px">' +
        '<div class="card-title">Credentials</div>' +
        '<div class="metric"><span class="metric-label">Email / username</span><input id="loginPrincipal" style="border:1px solid #d4a574;border-radius:8px;padding:8px;width:100%" value="' + esc(opts.defaultPrincipal || '') + '"></div>' +
        '<div class="metric"><span class="metric-label">Password</span><input id="loginPassword" type="password" style="border:1px solid #d4a574;border-radius:8px;padding:8px;width:100%"></div>' +
        '<div style="margin-top:8px;margin-bottom:4px"><label><input id="loginKeep" type="checkbox" checked> Keep me signed in</label></div>' +
        '<button id="loginBtn" style="margin-top:12px;padding:10px 18px;border:none;border-radius:8px;background:linear-gradient(135deg,#2d6a4f,#386395);color:#fff;font-weight:700;cursor:pointer">Login</button>' +
        '<div id="loginError" style="margin-top:12px;color:#8b4444;font-weight:700"></div></div>';
      document.getElementById('loginBtn').onclick = async function () {
        const principal = document.getElementById('loginPrincipal').value.trim();
        const password = document.getElementById('loginPassword').value;
        document.getElementById('loginError').textContent = '';
        try {
          await client.login(principal, password);
          await bootstrap();
        } catch (e) {
          document.getElementById('loginError').textContent = e.message || String(e);
        }
      };
    }

    async function refreshData() {
      if (!client.getToken()) return;
      cache = await opts.fetchData(client, fmt, safeAll);
      setStatus(true, cache.__latency, cache.__detail || '');
      if (currentPage) renderPage(currentPage);
    }

    function destroyChart() {
      if (chartRef) { chartRef.destroy(); chartRef = null; }
    }

    function renderChart(canvasId, labels, values, label) {
      destroyChart();
      const canvas = document.getElementById(canvasId);
      if (!canvas || !global.Chart || !labels?.length) return;
      chartRef = new Chart(canvas, {
        type: 'line',
        data: {
          labels: labels,
          datasets: [{
            label: label || 'Series',
            data: values,
            borderColor: '#2d6a4f',
            backgroundColor: 'rgba(45,106,79,0.1)',
            borderWidth: 3,
            fill: true,
            tension: 0.4,
          }],
        },
        options: {
          responsive: true, maintainAspectRatio: false,
          plugins: { legend: { labels: { color: '#2d6a4f', font: { weight: 'bold' } } } },
          scales: {
            y: { ticks: { color: '#6a8a7a' }, grid: { color: 'rgba(45,106,79,0.08)' } },
            x: { ticks: { color: '#6a8a7a' }, grid: { color: 'rgba(45,106,79,0.08)' } },
          },
        },
      });
    }

    function renderPage(pageName) {
      currentPage = pageName;
      const area = document.getElementById('contentArea');
      const html = opts.renderPage(pageName, cache, fmt, table, metricsCard, grid, client, toast, showModal, closeModal);
      area.innerHTML = html;
      if (typeof opts.afterRender === 'function') opts.afterRender(pageName, cache, renderChart, client, toast, showModal);
    }

    global.loadPage = function (pageName) {
      document.querySelectorAll('.menu-item').forEach(function (item) { item.classList.remove('active'); });
      if (global.event && global.event.target && global.event.target.classList) global.event.target.classList.add('active');
      if (!client.getToken()) { showLogin(); return; }
      renderPage(pageName);
    };

    async function bootstrap() {
      if (!client.getToken()) { showLogin(); return; }
      try {
        await refreshData();
        renderPage(currentPage);
        if (refreshTimer) clearInterval(refreshTimer);
        refreshTimer = setInterval(refreshData, CONFIG.REFRESH_MS);
      } catch (e) {
        setStatus(false, 0, e.message || String(e));
        if (String(e.message || e).includes('401')) { client.clearSession(); showLogin(); }
      }
    }

    global.addEventListener('load', function () { bootstrap(); });
    return { client: client, refreshData: refreshData, bootstrap: bootstrap, renderPage: renderPage };
  }

  /* ========================================================================
     TRADER DATA + PAGES
     ======================================================================== */
  async function fetchTraderData(client, fmt, safeAll) {
    const started = performance.now();
    const modeRes = await client.get('/trader/me/execution-mode').catch(function () { return { json: { data: { executionMode: 'PAPER' } } }; });
    const mode = String(modeRes.json?.data?.executionMode || 'PAPER').toUpperCase();
    const data = await safeAll(client, [
      ['dashboard', '/portfolio/dashboard?equityPoints=60'],
      ['overview', '/portfolio/overview'],
      ['workstation', '/trader/terminal/workstation'],
      ['orders', '/oms/orders?page=0&size=50&sort=createdAt,desc&executionMode=' + encodeURIComponent(mode)],
      ['executions', '/oms/executions?page=0&size=50&sort=executedAt,desc'],
      ['watch', '/trader/terminal/market/watch'],
      ['strategies', '/strategies/runtime-metrics'],
      ['catalog', '/strategies/catalog?size=48'],
      ['signals', '/trader/strategy-feed?limit=50'],
      ['broker', '/trader/broker/status'],
      ['execSummary', '/trader/execution-summary'],
      ['profile', '/users/me/profile'],
      ['exposure', '/portfolio/exposure'],
    ]);
    data.executionMode = mode;
    data.__latency = Math.round(performance.now() - started);
    const brokerState = String(data.broker?.health || data.broker?.status || 'UNKNOWN').toUpperCase();
    data.__detail = mode + ' \u00B7 Broker ' + brokerState;
    return data;
  }

  function renderTraderPage(page, c, fmt, table, metricsCard, grid) {
    const ov = c.dashboard?.overview || c.overview || {};
    const positions = Array.isArray(c.workstation?.openPositions) ? c.workstation.openPositions
      : Array.isArray(c.workstation?.positions) ? c.workstation.positions : [];
    const orders = c.orders?.content || [];
    const executions = c.executions?.content || [];
    const watch = Array.isArray(c.watch) ? c.watch : [];
    const strategies = Array.isArray(c.strategies) ? c.strategies : [];
    const signals = Array.isArray(c.signals) ? c.signals : [];
    const profile = c.profile || {};

    switch (page) {
      case 'dashboard': {
        const eqPoints = c.dashboard?.equityCurve || c.dashboard?.equitySeries || c.dashboard?.equityPoints || [];
        const chartBlock = '<div class="chart-container"><h3>Portfolio Performance</h3><div class="chart-wrapper"><canvas id="chartCanvas"></canvas></div></div>';
        c.__chart = {
          labels: eqPoints.map(function (p) { return fmt.time(p.asOf || p.time || p.ts || p.label); }),
          values: eqPoints.map(function (p) { return Number(p.cumulativePnl ?? p.equity ?? p.value ?? p.close ?? 0); }),
          label: 'Cumulative P&L',
        };
        return (
          '<div class="page-title">Trading Dashboard</div><div class="page-subtitle">Live data \u00B7 ' + fmt.text(c.executionMode) + '</div>' +
          grid(
            metricsCard('Portfolio', [
              ['Total P&L', fmt.money(ov.mtmPnl)],
              ['Unrealized', fmt.money(ov.unrealizedPnl)],
              ['Realized', fmt.money(ov.realizedPnl)],
              ['Capital', fmt.money(ov.totalCapital || ov.accountValue)],
            ]) +
            metricsCard('Session', [
              ['Open Positions', positions.length],
              ['Orders Today', fmt.text(c.execSummary?.ordersTotal, '0')],
              ['Rejected', fmt.text(c.execSummary?.rejectedOrders, '0')],
              ['Broker', fmt.text(c.broker?.health || c.broker?.status, 'UNKNOWN')],
            ]) +
            metricsCard('Margin', [
              ['Equity', fmt.money(ov.totalEquity || ov.accountValue)],
              ['Available', fmt.money(ov.availableMargin || ov.cashAvailable)],
              ['Used %', fmt.num(ov.marginUsedPct, 1) + '%'],
              ['Mode', fmt.text(c.executionMode)],
            ])
          ) + chartBlock
        );
      }
      case 'overview':
        return (
          '<div class="page-title">Portfolio Overview</div><div class="page-subtitle">Account breakdown</div>' +
          grid(
            metricsCard('Account', [
              ['Account Value', fmt.money(ov.accountValue || ov.totalEquity)],
              ['Cash', fmt.money(ov.cashAvailable || ov.availableMargin)],
              ['MTM P&L', fmt.money(ov.mtmPnl)],
              ['Day Change', fmt.text(ov.dayChangeLabel, '-')],
            ]) +
            metricsCard('Exposure', [
              ['Gross', fmt.money(c.exposure?.grossExposure)],
              ['Net', fmt.money(c.exposure?.netExposure)],
              ['Long', fmt.money(c.exposure?.longExposure)],
              ['Short', fmt.money(c.exposure?.shortExposure)],
            ]) +
            metricsCard('Profile', [
              ['Name', fmt.text(profile.displayName || profile.username)],
              ['Email', fmt.text(profile.email)],
              ['Live Approved', profile.liveTradingApproved ? 'Yes' : 'No'],
              ['Mode', fmt.text(c.executionMode)],
            ])
          )
        );
      case 'orders':
        return (
          '<div class="page-title">Orders</div><div class="page-subtitle">Recent OMS orders</div><div class="card"><div class="card-title">Order History</div>' +
          table(['ID', 'Symbol', 'Side', 'Qty', 'State', 'Time'], orders.map(function (o) { return [
            fmt.text(o.id || o.orderId).slice(0, 8),
            fmt.text(o.symbol),
            fmt.text(o.side),
            fmt.text(o.quantity || o.qty),
            fmt.statusBadge ? fmt.statusBadge(o.state || o.status) : fmt.text(o.state || o.status),
            fmt.time(o.createdAt),
          ]; })) + '</div>'
        );
      case 'positions':
        return (
          '<div class="page-title">Open Positions</div><div class="page-subtitle">Workstation positions</div><div class="card"><div class="card-title">Positions</div>' +
          table(['Symbol', 'Qty', 'Avg', 'LTP', 'P&L', 'Mode'], positions.map(function (p) { return [
            fmt.text(p.symbol),
            fmt.text(p.quantity || p.qty),
            fmt.num(p.avgPrice || p.averagePrice, 2),
            fmt.num(p.ltp || p.lastPrice, 2),
            fmt.money(p.unrealizedPnl || p.pnl),
            fmt.text(p.executionMode || c.executionMode),
          ]; })) + '</div>'
        );
      case 'executions':
        return (
          '<div class="page-title">Executions</div><div class="page-subtitle">Fill history</div><div class="card"><div class="card-title">Executions</div>' +
          table(['Time', 'Symbol', 'Side', 'Qty', 'Price', 'Value'], executions.map(function (e) { return [
            fmt.time(e.executedAt || e.createdAt),
            fmt.text(e.symbol),
            fmt.text(e.side),
            fmt.text(e.quantity || e.qty),
            fmt.num(e.price, 2),
            fmt.money((Number(e.price || 0) * Number(e.quantity || e.qty || 0)) || e.value),
          ]; })) + '</div>'
        );
      case 'watchlist':
        return (
          '<div class="page-title">Watchlist</div><div class="page-subtitle">Market watch</div><div class="card"><div class="card-title">Symbols</div>' +
          table(['Symbol', 'Price', 'Change %', 'Volume'], watch.map(function (w) { return [
            fmt.text(w.symbol),
            fmt.num(w.price || w.ltp, 2),
            fmt.pct(w.changePct),
            fmt.text(w.volume),
          ]; })) + '</div>'
        );
      case 'strategies':
        return (
          '<div class="page-title">Strategy Manager</div><div class="page-subtitle">Runtime metrics</div>' +
          grid(
            metricsCard('Summary', [
              ['Active Strategies', strategies.filter(function (s) { return String(s.executionMode).toUpperCase() === c.executionMode; }).length],
              ['Catalog Subscribed', (c.catalog?.content || []).filter(function (x) { return x.subscribed; }).length],
              ['Mode Filter', c.executionMode],
              ['Feed', fmt.text(c.broker?.health || c.broker?.status)],
            ]) +
            '<div class="card"><div class="card-title">Runtime</div>' +
            table(['Strategy', 'Mode', 'Signals', 'Unrealized P&L'], strategies.slice(0, 20).map(function (s) { return [
              fmt.text(s.strategyKey || s.name),
              fmt.text(s.executionMode),
              fmt.text(s.signalsToday || s.signalCount || '-'),
              fmt.money(s.unrealizedPnl),
            ]; })) + '</div>'
          )
        );
      case 'signals':
        return (
          '<div class="page-title">Signal Monitor</div><div class="page-subtitle">Recent strategy signals</div><div class="card"><div class="card-title">Signals</div>' +
          table(['Time', 'Strategy', 'Symbol', 'Type', 'State'], signals.map(function (s) { return [
            fmt.time(s.createdAt),
            fmt.text(s.strategyName || s.strategyKey),
            fmt.text(s.symbol),
            fmt.text(s.signalType || s.side),
            fmt.text(s.state || s.status),
          ]; })) + '</div>'
        );
      case 'broker':
        return (
          '<div class="page-title">Broker Connect</div><div class="page-subtitle">Broker integration status</div>' +
          grid(metricsCard('Broker', [
            ['Health', fmt.text(c.broker?.health || c.broker?.status)],
            ['Linked', c.broker?.connected || c.broker?.linked ? 'Yes' : 'No'],
            ['Vendor', fmt.text(c.broker?.vendor || c.broker?.brokerCode, 'Zerodha')],
            ['Last Sync', fmt.time(c.broker?.lastSyncAt || c.broker?.updatedAt)],
          ]))
        );
      case 'settings':
        return (
          '<div class="page-title">Preferences</div><div class="page-subtitle">Execution preferences</div>' +
          metricsCard('Settings', [
            ['Execution Mode', c.executionMode],
            ['Theme', 'Nature Organic'],
            ['API', CONFIG.API_BASE],
            ['Refresh', CONFIG.REFRESH_MS / 1000 + 's'],
          ])
        );
      case 'profile':
        return (
          '<div class="page-title">Profile</div><div class="page-subtitle">Account information</div>' +
          metricsCard('Profile', [
            ['Name', fmt.text(profile.displayName || profile.username)],
            ['Email', fmt.text(profile.email)],
            ['Roles', (profile.roles || []).join(', ') || '-'],
            ['Live Approved', profile.liveTradingApproved ? 'Yes' : 'No'],
          ])
        );
      default:
        return '<div class="page-title">Not found</div>';
    }
  }

  /* ========================================================================
     ADMIN DATA + PAGES
     ======================================================================== */
  async function fetchAdminData(client, fmt, safeAll) {
    const started = performance.now();
    const data = await safeAll(client, [
      ['health', '/admin/health'],
      ['users', '/admin/users?page=0&size=50'],
      ['ops', '/admin/ops/status'],
      ['readiness', '/admin/readiness'],
      ['oms', '/admin/oms/summary'],
      ['omsOrders', '/admin/oms/orders?page=0&size=30&sort=createdAt,desc'],
      ['audit', '/admin/audit?page=0&size=30'],
      ['alerts', '/admin/alerts'],
      ['strategies', '/admin/strategies?size=50'],
      ['snapshot', '/admin/operations/snapshot'],
      ['settings', '/admin/settings/summary'],
      ['risk', '/admin/risk-dashboard'],
      ['signals', '/admin/signals?page=0&size=30'],
      ['bindings', '/admin/runtime-bindings?size=50'],
      ['universe', '/admin/universe-groups?size=50'],
    ]);
    data.__latency = Math.round(performance.now() - started);
    var usersTotal = data.users?.totalElements ?? data.users?.length ?? 0;
    data.__detail = 'Users ' + usersTotal + ' \u00B7 Strategies ' + (data.ops?.runningStrategies ?? '-') + ' \u00B7 Alerts ' + (Array.isArray(data.alerts) ? data.alerts.length : '-');
    return data;
  }

  /* ---- Admin Action Helpers ---- */
  function adminActions(client, toast) {
    return {
      async toggleUser(userId, enabled) {
        await client.patch('/admin/users/' + userId + '/status', { enabled: enabled });
        toast('User ' + (enabled ? 'enabled' : 'disabled'), 'success');
      },
      async toggleLiveTrading(userId, approved) {
        await client.patch('/admin/users/' + userId + '/live-trading', { liveTradingApproved: approved });
        toast('Live trading ' + (approved ? 'approved' : 'revoked'), 'success');
      },
      async resetPassword(userId) {
        var result = await client.patch('/admin/users/' + userId + '/reset-password');
        var tempPw = result?.json?.data?.temporaryPassword || result?.json?.data?.password || 'Reset completed';
        toast('Password reset: ' + tempPw, 'success');
      },
      async toggleKillSwitch(activate) {
        await client.post('/admin/oms/kill-switch/' + (activate ? 'activate' : 'deactivate'), { reason: 'Admin action from panel' });
        toast('Kill switch ' + (activate ? 'ACTIVATED' : 'deactivated'), activate ? 'error' : 'success');
      },
      async armLiveTrading(arm) {
        await client.post('/admin/live-trading/arm', { armed: arm });
        toast('Live trading ' + (arm ? 'ARMED' : 'disarmed'), arm ? 'error' : 'success');
      },
      async toggleStrategy(strategyKey, enabled) {
        await client.post('/admin/strategy/toggle', { strategyKey: strategyKey, enabled: enabled });
        toast('Strategy ' + strategyKey + ' ' + (enabled ? 'enabled' : 'disabled'), 'success');
      },
      async acknowledgeAlert(alertId) {
        await client.post('/admin/reconciliation/events/' + alertId + '/acknowledge');
        toast('Alert acknowledged', 'success');
      },
    };
  }

  function renderAdminPage(page, c, fmt, table, metricsCard, grid, client, toast, showModal) {
    const actions = adminActions(client, toast);
    const users = c.users?.content || [];
    const alerts = Array.isArray(c.alerts) ? c.alerts : [];
    const audit = Array.isArray(c.audit) ? c.audit : [];
    const strategies = Array.isArray(c.strategies) ? (c.strategies.content || c.strategies) : [];
    const snap = c.snapshot || {};
    const omsSnap = snap.oms || {};
    const orders = c.omsOrders?.content || [];
    const signals = Array.isArray(c.signals) ? (c.signals.content || c.signals) : [];
    const risk = c.risk || {};

    function userRow(u) {
      return [
        fmt.text(u.displayName || u.username),
        fmt.text(u.email).slice(0, 24),
        u.enabled
          ? '<span style="color:#2d6a4f;font-weight:700">Yes</span>'
          : '<span style="color:#999">No</span>',
        u.liveTradingApproved
          ? '<span style="color:#386395;font-weight:700">Approved</span>'
          : '<span style="color:#999">Pending</span>',
        u.brokerLinked ? '<span style="color:#2d6a4f">Linked</span>' : '<span style="color:#999">No</span>',
        '<div style="display:flex;gap:4px">' +
          '<button class="action-btn toggle-btn" data-id="' + u.id + '" data-action="toggle-user" style="padding:4px 10px;border:none;border-radius:6px;background:' + (u.enabled ? '#f0f0f0' : '#2d6a4f') + ';color:' + (u.enabled ? '#666' : '#fff') + ';font-weight:700;cursor:pointer;font-size:11px">' + (u.enabled ? 'Disable' : 'Enable') + '</button>' +
          '<button class="action-btn" data-id="' + u.id + '" data-action="live-trade" style="padding:4px 10px;border:none;border-radius:6px;background:' + (u.liveTradingApproved ? '#386395' : '#f0f0f0') + ';color:' + (u.liveTradingApproved ? '#fff' : '#666') + ';font-weight:700;cursor:pointer;font-size:11px">' + (u.liveTradingApproved ? 'Revoke Live' : 'Approve Live') + '</button>' +
          '<button class="action-btn" data-id="' + u.id + '" data-action="reset-pw" style="padding:4px 10px;border:none;border-radius:6px;background:#d4a574;color:#fff;font-weight:700;cursor:pointer;font-size:11px">Reset PW</button>' +
        '</div>',
      ];
    }

    function strategyRow(s) {
      return [
        '<strong>' + fmt.text(s.code) + '</strong>',
        fmt.text(s.displayName || s.name),
        '<span style="color:' + (s.supportsLive ? '#2d6a4f' : '#999') + ';font-weight:700">' + (s.supportsLive ? 'Yes' : 'No') + '</span>',
        '<span style="color:' + (s.enabled ? '#2d6a4f' : '#999') + ';font-weight:700">' + (s.enabled ? 'Enabled' : 'Disabled') + '</span>',
        '<div style="display:flex;gap:4px">' +
          '<button class="action-btn strat-toggle" data-key="' + esc(s.code) + '" data-enabled="' + s.enabled + '" style="padding:4px 10px;border:none;border-radius:6px;background:' + (s.enabled ? '#f0f0f0' : '#2d6a4f') + ';color:' + (s.enabled ? '#666' : '#fff') + ';font-weight:700;cursor:pointer;font-size:11px">' + (s.enabled ? 'Disable' : 'Enable') + '</button>' +
        '</div>',
      ];
    }

    function signalRow(s) {
      return [
        fmt.time(s.createdAt),
        fmt.text(s.strategyKey || s.strategyName),
        fmt.text(s.symbol),
        '<span style="color:' + (String(s.state || s.status).includes('LIVE') ? '#386395' : '#2d6a4f') + ';font-weight:600">' + fmt.text(s.state || s.status) + '</span>',
        fmt.text(s.signalType || s.side || s.direction),
        '<span style="color:' + (String(s.outcomeStatus || '').includes('TIME') ? '#d4a574' : '#999') + ';font-size:11px">' + fmt.text(s.outcomeStatus, '-') + '</span>',
      ];
    }

    function orderRow(o) {
      return [
        fmt.text(o.id || o.orderId).slice(0, 8),
        fmt.text(o.symbol),
        fmt.text(o.side),
        fmt.text(o.quantity || o.qty),
        fmt.statusBadge ? fmt.statusBadge(o.state || o.status) : fmt.text(o.state || o.status),
        fmt.time(o.createdAt),
      ];
    }

    function alertRow(a) {
      return [
        fmt.text(a.level || a.severity),
        fmt.text(a.title || a.code),
        fmt.text(a.subsystem || a.source),
        fmt.time(a.detectedAt || a.createdAt),
        '<button class="action-btn ack-alert" data-id="' + (a.id || '') + '" style="padding:4px 10px;border:none;border-radius:6px;background:#2d6a4f;color:#fff;font-weight:700;cursor:pointer;font-size:11px">Acknowledge</button>',
      ];
    }

    switch (page) {
      /* ---- Dashboard ---- */
      case 'dashboard': {
        c.__chart = {
          labels: ['Orders', 'Rejected', 'Cancelled', 'Fills'],
          values: [
            Number(c.oms?.totalOrders || 0),
            Number(c.oms?.rejectedOrders || 0),
            Number(c.oms?.cancelledOrders || 0),
            Number(c.oms?.fillLegs || 0),
          ],
          label: 'OMS Totals',
        };
        return (
          '<div class="page-title">Admin Dashboard</div><div class="page-subtitle">Live platform metrics</div>' +
          grid(
            metricsCard('Platform', [
              ['Total Users', fmt.text(c.users?.totalElements)],
              ['Running Strategies', fmt.text(c.ops?.runningStrategies)],
              ['Registered Users', fmt.text(c.ops?.registeredUsers)],
              ['WS Users', fmt.text(c.ops?.websocketUsersApprox)],
            ]) +
            metricsCard('Risk / Live', [
              ['Kill Switch', c.health?.killSwitch
                ? '<span style="color:#c44;font-weight:900">ENABLED</span>'
                : '<span style="color:#2d6a4f">OFF</span>'],
              ['Live Armed', c.health?.liveTradingArmed ? 'ARMED' : 'DISARMED'],
              ['Uptime', fmt.text(c.health?.uptimeSeconds) + 's'],
              ['Branch', fmt.text(c.ops?.deployBranch)],
            ]) +
            metricsCard('OMS', [
              ['Total Orders', fmt.text(c.oms?.totalOrders)],
              ['Rejected', fmt.text(c.oms?.rejectedOrders)],
              ['Avg Latency', fmt.num(c.oms?.averageLatencyMs, 0) + 'ms'],
              ['Fill Legs', fmt.text(c.oms?.fillLegs)],
            ])
          ) +
          '<div class="chart-container"><h3>OMS Overview</h3><div class="chart-wrapper"><canvas id="chartCanvas"></canvas></div></div>' +
          '<div style="display:flex;gap:12px;flex-wrap:wrap;margin-top:16px">' +
          '<button id="killSwitchBtn" style="padding:12px 24px;border:none;border-radius:8px;background:' + (c.health?.killSwitch ? '#c44' : '#2d6a4f') + ';color:#fff;font-weight:800;font-size:14px;cursor:pointer">' + (c.health?.killSwitch ? 'DEACTIVATE KILL SWITCH' : 'ACTIVATE KILL SWITCH') + '</button>' +
          '<button id="armLiveBtn" style="padding:12px 24px;border:none;border-radius:8px;background:' + (c.health?.liveTradingArmed ? '#999' : '#386395') + ';color:#fff;font-weight:800;font-size:14px;cursor:pointer">' + (c.health?.liveTradingArmed ? 'DISARM LIVE TRADING' : 'ARM LIVE TRADING') + '</button>' +
          '</div>'
        );
      }

      /* ---- Platform Metrics ---- */
      case 'metrics':
        return (
          '<div class="page-title">Platform Metrics</div><div class="page-subtitle">Operations snapshot</div>' +
          grid(
            metricsCard('OMS Snapshot', [
              ['Orders Total', fmt.text(omsSnap.ordersTotal)],
              ['Reject Rate', fmt.num(omsSnap.rejectRateApprox, 1) + '%'],
              ['Signals Total', fmt.text(omsSnap.signalsPersistedTotal)],
              ['Avg Latency', fmt.num(omsSnap.executionAvgLatencyMs, 0) + 'ms'],
            ]) +
            metricsCard('Market Infra', [
              ['Freshness', fmt.text(snap.marketInfra?.freshnessStatus)],
              ['Global Halt', snap.marketInfra?.globalBrokerHalt ? 'YES' : 'NO'],
              ['Ticks/min', fmt.text(snap.marketInfra?.ticksIngestedLast60sPlatformWs)],
              ['Plane', fmt.text(snap.marketInfra?.plane)],
            ]) +
            metricsCard('Risk Dashboard', [
              ['Kill Switch', risk.killSwitchActive ? 'ACTIVE' : 'Off'],
              ['Live Armed', risk.liveTradingArmed ? 'Armed' : 'Disarmed'],
              ['Today Orders', fmt.text(risk.todayOrders)],
              ['Today Fills', fmt.text(risk.todayFills)],
            ])
          )
        );

      /* ---- Users ---- */
      case 'users':
        return (
          '<div class="page-title">User Management</div><div class="page-subtitle">Registered traders (' + users.length + ')</div><div class="card"><div class="card-title">Users <span style="float:right;font-size:12px;font-weight:400;color:#999">Actions: Enable/Disable | Approve Live Trading | Reset Password</span></div>' +
          table(['Name', 'Email', 'Enabled', 'Live Trading', 'Broker', 'Actions'], users.map(function (u) { return userRow(u); })) +
          '</div>'
        );

      /* ---- Trading Accounts ---- */
      case 'accounts':
        return (
          '<div class="page-title">Trading Accounts</div><div class="page-subtitle">User account overview</div><div class="card"><div class="card-title">Accounts</div>' +
          table(['User', 'Email', 'Live Approved', 'Broker', 'Active Strategies'], users.map(function (u) { return [
            fmt.text(u.displayName || u.username),
            fmt.text(u.email),
            u.liveTradingApproved
              ? '<span style="color:#2d6a4f;font-weight:700">Approved</span>'
              : '<span style="color:#999">Pending</span>',
            u.brokerLinked ? '<span style="color:#2d6a4f">Linked</span>' : '<span style="color:#999">No</span>',
            fmt.text(u.activeStrategies),
          ]; })) + '</div>'
        );

      /* ---- Permissions ---- */
      case 'permissions':
        return (
          '<div class="page-title">Permissions</div><div class="page-subtitle">Role assignments</div><div class="card"><div class="card-title">Roles</div>' +
          table(['User', 'Email', 'Roles'], users.map(function (u) { return [
            fmt.text(u.displayName || u.username),
            fmt.text(u.email),
            (u.roles || []).join(', '),
          ]; })) + '</div>'
        );

      /* ---- Strategies ---- */
      case 'strategies':
        return (
          '<div class="page-title">Strategy Management</div><div class="page-subtitle">Catalog strategies (' + strategies.length + ')</div><div class="card"><div class="card-title">Strategies <span style="float:right;font-size:12px;font-weight:400;color:#999">Click Enable/Disable to toggle strategy</span></div>' +
          table(['Code', 'Name', 'Supports Live', 'Status', 'Actions'], strategies.map(function (s) { return strategyRow(s); })) +
          '</div>'
        );

      /* ---- Broker Connections ---- */
      case 'brokers':
        return (
          '<div class="page-title">Broker Connections</div><div class="page-subtitle">Linked broker accounts</div>' +
          grid(
            metricsCard('Brokers', [
              ['Linked Users', users.filter(function (u) { return u.brokerLinked; }).length + ' / ' + users.length],
              ['Global Halt', snap.marketInfra?.globalBrokerHalt
                ? '<span style="color:#c44;font-weight:900">YES</span>'
                : '<span style="color:#2d6a4f">NO</span>'],
              ['Feed State', fmt.text(c.settings?.marketFeedState)],
              ['Subscriptions', fmt.text(c.settings?.marketFeedSubscriptions)],
            ]) +
            metricsCard('Broker Infrastructure', [
              ['Zerodha Sessions', fmt.text(c.settings?.zerodhaSessions)],
              ['Refresh Token', c.settings?.zerodhaRefreshTokenValid ? 'Valid' : 'Expired'],
              ['WebSocket', c.settings?.zerodhaWebSocketConnected ? 'Connected' : 'Disconnected'],
              ['Last Reconnect', fmt.time(c.settings?.zerodhaLastReconnect)],
            ])
          )
        );

      /* ---- Reports ---- */
      case 'reports':
        return (
          '<div class="page-title">Reports</div><div class="page-subtitle">OMS and execution summary</div>' +
          grid(
            metricsCard('OMS Report', [
              ['Total Orders', fmt.text(c.oms?.totalOrders)],
              ['Rejected', fmt.text(c.oms?.rejectedOrders)],
              ['Cancelled', fmt.text(c.oms?.cancelledOrders)],
              ['Avg Slippage', fmt.num(c.oms?.averageSlippageBps, 2) + ' bps'],
            ]) +
            metricsCard('Today OMS', [
              ['Orders', fmt.text(risk.todayOrders)],
              ['Fills', fmt.text(risk.todayFills)],
              ['Rejects', fmt.text(risk.todayRejects)],
              ['Open Alerts', alerts.length],
            ])
          )
        );

      /* ---- Signal Monitor ---- */
      case 'signal-monitor':
        return (
          '<div class="page-title">Signal Monitor</div><div class="page-subtitle">Recent strategy signals (' + signals.length + ')</div><div class="card"><div class="card-title">Signals</div>' +
          table(['Time', 'Strategy', 'Symbol', 'State', 'Type', 'Outcome'], signals.map(function (s) { return signalRow(s); })) +
          '</div>' +
          '<div style="display:flex;gap:12px;margin-top:16px">' +
          '<button class="action-btn" id="backfillOutcomesBtn" style="padding:10px 20px;border:none;border-radius:8px;background:#2d6a4f;color:#fff;font-weight:700;cursor:pointer">Backfill Outcomes</button>' +
          '<button class="action-btn" id="backfillExitsBtn" style="padding:10px 20px;border:none;border-radius:8px;background:#386395;color:#fff;font-weight:700;cursor:pointer">Backfill Exit Orders</button>' +
          '</div>'
        );

      /* ---- OMS Monitor ---- */
      case 'oms-monitor':
        return (
          '<div class="page-title">OMS Monitor</div><div class="page-subtitle">Recent orders (' + orders.length + ')</div><div class="card"><div class="card-title">Orders</div>' +
          table(['ID', 'Symbol', 'Side', 'Qty', 'State', 'Time'], orders.map(function (o) { return orderRow(o); })) +
          '</div>' +
          grid(
            metricsCard('OMS Summary', [
              ['Total Orders', fmt.text(c.oms?.totalOrders)],
              ['Rejected', fmt.text(c.oms?.rejectedOrders)],
              ['Fill Legs', fmt.text(c.oms?.fillLegs)],
              ['Avg Latency', fmt.num(c.oms?.averageLatencyMs, 0) + 'ms'],
            ]) +
            metricsCard('Reject Analysis', [
              ['Reject Rate', fmt.num(c.oms?.rejectedOrders / Math.max(Number(c.oms?.totalOrders || 1), 1) * 100, 1) + '%'],
              ['Reject Reasons', '<a href="#" onclick="loadPage(\'reject-reasons\');return false">View</a>'],
              ['Stuck Orders', '<a href="#" onclick="loadPage(\'stuck-orders\');return false">Expire Stuck</a>'],
              ['Position Recon', '<a href="#" onclick="loadPage(\'position-recon\');return false">View</a>'],
            ])
          )
        );

      /* ---- Alerts / Monitoring ---- */
      case 'monitoring':
        return (
          '<div class="page-title">System Monitoring</div><div class="page-subtitle">Readiness checks & alerts</div>' +
          grid(
            metricsCard('Readiness', Object.entries(c.readiness?.checks || {}).slice(0, 8).map(function (pair) {
              return [pair[0], pair[1]?.ok
                ? '<span style="color:#2d6a4f;font-weight:700">OK</span>'
                : '<span style="color:#c44;font-weight:700">FAIL</span>'];
            })) +
            metricsCard('Alerts', alerts.slice(0, 6).map(function (a) { return [a.title || a.code, a.level || 'info']; }))
          ) +
          '<div class="card" style="margin-top:16px"><div class="card-title">Active Alerts</div>' +
          table(['Level', 'Title', 'Subsystem', 'Detected', 'Action'], alerts.slice(0, 20).map(function (a) { return alertRow(a); })) +
          '</div>'
        );

      /* ---- Audit Logs ---- */
      case 'logs':
        return (
          '<div class="page-title">System Logs</div><div class="page-subtitle">Audit trail</div><div class="card"><div class="card-title">Audit Events</div>' +
          table(['Time', 'Action', 'Actor', 'Resource', 'Detail'], audit.map(function (a) { return [
            fmt.time(a.createdAt),
            fmt.text(a.action),
            fmt.text(a.actorUserId).slice(0, 8),
            fmt.text(a.resourceType),
            fmt.text(a.detail || a.description || '').slice(0, 40),
          ]; })) + '</div>'
        );

      /* ---- Security ---- */
      case 'security':
        return (
          '<div class="page-title">Security</div><div class="page-subtitle">Platform alerts & posture</div>' +
          grid(
            metricsCard('Posture', [
              ['Kill Switch', risk.killSwitchActive ? 'ACTIVE' : 'Off'],
              ['Live Trading', risk.liveTradingArmed ? 'Armed' : 'Disarmed'],
              ['Alerts Active', alerts.length],
              ['Audit Events', audit.length],
            ]) +
            metricsCard('Security Summary', [
              ['Alert Sources', [...new Set(alerts.map(function (a) { return a.subsystem || 'unknown'; }))].join(', ')],
              ['Recent Events', fmt.time(audit[0]?.createdAt)],
              ['Uptime', fmt.text(c.health?.uptimeSeconds) + 's'],
              ['API Base', CONFIG.API_BASE],
            ])
          ) +
          '<div class="card" style="margin-top:16px"><div class="card-title">Active Alerts</div>' +
          table(['Level', 'Title', 'Subsystem', 'Detected'], alerts.map(function (a) { return [
            a.level === 'CRITICAL' || a.level === 'ERROR'
              ? '<span style="color:#c44;font-weight:700">' + a.level + '</span>'
              : '<span style="color:#d4a574">' + a.level + '</span>',
            fmt.text(a.title),
            fmt.text(a.subsystem),
            fmt.time(a.detectedAt),
          ]; })) + '</div>'
        );

      /* ---- Configuration ---- */
      case 'config':
        return (
          '<div class="page-title">Configuration</div><div class="page-subtitle">Runtime settings summary</div>' +
          grid(
            metricsCard('System Settings', [
              ['Kill Switch', c.settings?.killSwitch ? 'ACTIVE' : 'Inactive'],
              ['Live Armed', c.settings?.liveTradingArmed ? 'Armed' : 'Disarmed'],
              ['Uptime', fmt.text(c.settings?.uptimeHuman || (c.health?.uptimeSeconds + 's'))],
              ['Strategies Total', fmt.text(c.settings?.strategiesTotal)],
              ['Market Feed', fmt.text(c.settings?.marketFeedState)],
              ['API', CONFIG.API_BASE],
            ]) +
            metricsCard('Execution Config', [
              ['Orders Total', fmt.text(c.oms?.totalOrders)],
              ['Rejected Orders', fmt.text(c.oms?.rejectedOrders)],
              ['Cancelled Orders', fmt.text(c.oms?.cancelledOrders)],
              ['Avg Slippage', fmt.num(c.oms?.averageSlippageBps, 1) + ' bps'],
            ])
          )
        );

      /* ---- Risk Dashboard ---- */
      case 'risk-dashboard':
        return (
          '<div class="page-title">Risk Dashboard</div><div class="page-subtitle">Strategy risk state & exposure</div>' +
          grid(
            metricsCard('Risk Status', [
              ['Kill Switch', risk.killSwitchActive ? 'ACTIVE' : 'Off'],
              ['Live Armed', risk.liveTradingArmed ? 'Armed' : 'Disarmed'],
              ['Broker Halt', risk.brokerHalt ? 'YES' : 'No'],
              ['Open Alerts', (risk.openReconciliationAlerts || alerts.length)],
            ]) +
            metricsCard('Today Activity', [
              ['Orders', fmt.text(risk.todayOrders)],
              ['Fills', fmt.text(risk.todayFills)],
              ['Rejects', fmt.text(risk.todayRejects)],
              ['Fill Rate', risk.todayOrders
                ? fmt.num((risk.todayFills || 0) / risk.todayOrders * 100, 1) + '%'
                : '-'],
            ]) +
            metricsCard('OMS Health', [
              ['Total Orders', fmt.text(c.oms?.totalOrders)],
              ['Avg Latency', fmt.num(c.oms?.averageLatencyMs, 0) + 'ms'],
              ['Fill Legs', fmt.text(c.oms?.fillLegs)],
              ['Uptime', fmt.text(c.health?.uptimeSeconds) + 's'],
            ])
          )
        );

      /* ---- Reject Reasons ---- */
      case 'reject-reasons':
        return (
          '<div class="page-title">Reject Reasons</div><div class="page-subtitle">Top order reject reasons</div>' +
          metricsCard('Reject Analysis', [
            ['Total Rejected', fmt.text(c.oms?.rejectedOrders)],
            ['Reject Rate', c.oms?.totalOrders ? fmt.num(Number(c.oms?.rejectedOrders || 0) / Number(c.oms?.totalOrders || 1) * 100, 1) + '%' : '-'],
            ['Total Orders', fmt.text(c.oms?.totalOrders)],
            ['Avg Latency', fmt.num(c.oms?.averageLatencyMs, 0) + 'ms'],
          ])
        );

      /* ---- Stuck Orders ---- */
      case 'stuck-orders':
        return (
          '<div class="page-title">Stuck Orders</div><div class="page-subtitle">Force-expire stuck orders</div>' +
          '<div class="card"><div class="card-title">Action</div>' +
          '<p style="margin-bottom:12px;color:#666">Force-expire all orders stuck in pre-terminal states for more than 5 minutes.</p>' +
          '<button id="expireStuckBtn" style="padding:10px 20px;border:none;border-radius:8px;background:#c44;color:#fff;font-weight:700;cursor:pointer">Expire Stuck Orders</button>' +
          '</div>'
        );

      /* ---- Position Reconciliation ---- */
      case 'position-recon':
        return (
          '<div class="page-title">Position Reconciliation</div><div class="page-subtitle">OMS vs Broker positions</div>' +
          '<div class="card"><div class="card-title">Reconciliation</div>' +
          '<p style="margin-bottom:12px;color:#666">Compare OMS open legs with broker holdings and running signals.</p>' +
          '<button id="runReconBtn" style="padding:10px 20px;border:2px solid #2d6a4f;border-radius:8px;background:#fff;color:#2d6a4f;font-weight:700;cursor:pointer;margin-right:12px">Run Reconciliation</button>' +
          '<button id="clearGhostsBtn" style="padding:10px 20px;border:none;border-radius:8px;background:#386395;color:#fff;font-weight:700;cursor:pointer">Clear Ghost Positions</button>' +
          '</div>'
        );

      /* ---- Broker Infrastructure ---- */
      case 'broker-infrastructure':
        return (
          '<div class="page-title">Broker Infrastructure</div><div class="page-subtitle">Per-vendor broker state</div>' +
          grid(
            metricsCard('Zerodha', [
              ['Feed State', fmt.text(c.settings?.marketFeedState)],
              ['WebSocket', c.settings?.zerodhaWebSocketConnected ? 'Connected' : 'Disconnected'],
              ['Refresh Token', c.settings?.zerodhaRefreshTokenValid ? 'Valid' : 'Expired'],
              ['Last Reconnect', fmt.time(c.settings?.zerodhaLastReconnect)],
            ]) +
            metricsCard('Market Data', [
              ['Freshness', fmt.text(snap.marketInfra?.freshnessStatus)],
              ['Ticks/min', fmt.text(snap.marketInfra?.ticksIngestedLast60sPlatformWs)],
              ['Plane', fmt.text(snap.marketInfra?.plane)],
              ['Subscriptions', fmt.text(c.settings?.marketFeedSubscriptions)],
            ]) +
            metricsCard('Broker Summary', [
              ['Linked Users', users.filter(function (u) { return u.brokerLinked; }).length + ' / ' + users.length],
              ['Global Halt', snap.marketInfra?.globalBrokerHalt ? 'YES' : 'NO'],
              ['Broker Vendors', 'Zerodha'],
              ['OAuth Status', 'Connected'],
            ])
          )
        );

      /* ---- Live Logs (SSE) ---- */
      case 'live-logs':
        return (
          '<div class="page-title">Live Logs</div><div class="page-subtitle">Streaming application logs</div>' +
          '<div class="card"><div class="card-title">Log Stream <span style="float:right;font-size:11px;color:#999"><span style="display:inline-block;width:8px;height:8px;background:#2d6a4f;border-radius:50%;animation:growthPulse 2s infinite"></span> Live</span></div>' +
          '<div id="logContainer" style="background:#1a2a1a;color:#9caf88;padding:16px;border-radius:8px;font-family:monospace;font-size:12px;max-height:500px;overflow-y:auto;white-space:pre-wrap">Connecting to log stream...</div>' +
          '</div>'
        );

      /* ---- Strategy Effectiveness ---- */
      case 'strategy-effectiveness':
        return (
          '<div class="page-title">Strategy Effectiveness</div><div class="page-subtitle">Production scorecards</div>' +
          '<div class="card"><div class="card-title">Scorecard</div>' +
          '<p style="color:#666">Strategy effectiveness data is available on the main admin panel at /admin/strategy-effectiveness.</p>' +
          '<table><tr><th>Strategy</th><th>Status</th></tr>' +
          strategies.slice(0, 20).map(function (s) { return '<tr><td>' + fmt.text(s.code) + '</td><td>' + (s.enabled ? 'Enabled' : 'Disabled') + '</td></tr>'; }).join('') +
          '</table></div>'
        );

      default:
        return '<div class="page-title">' + page.charAt(0).toUpperCase() + page.slice(1) + '</div><div class="page-subtitle">Page under construction</div><div class="card"><p style="color:#999;padding:20px;text-align:center">This feature will be available in the next update. Use the main admin panel at <a href="/admin" style="color:#386395">/admin</a>.</p></div>';
    }
  }

  /* ---- Admin afterRender (event binding) ---- */
  function adminAfterRender(page, c, renderChart, client, toast) {
    const actions = adminActions(client, toast);

    /* Render chart for dashboard */
    if (page === 'dashboard' && c.__chart) {
      setTimeout(function () { renderChart('chartCanvas', c.__chart.labels, c.__chart.values, c.__chart.label); }, 50);
    }

    /* Kill switch button */
    var ksBtn = document.getElementById('killSwitchBtn');
    if (ksBtn) {
      ksBtn.onclick = async function () {
        var activate = !c.health?.killSwitch;
        var reason = prompt('Reason for ' + (activate ? 'activating' : 'deactivating') + ' kill switch (optional):');
        try {
          await client.post('/admin/oms/kill-switch/' + (activate ? 'activate' : 'deactivate'), { reason: reason || 'Admin panel action' });
          toast('Kill switch ' + (activate ? 'ACTIVATED' : 'deactivated'), activate ? 'error' : 'success');
          setTimeout(function () { loadPage('dashboard'); }, 1000);
        } catch (e) { toast('Error: ' + e.message, 'error'); }
      };
    }

    /* Arm live trading button */
    var armBtn = document.getElementById('armLiveBtn');
    if (armBtn) {
      armBtn.onclick = async function () {
        var arm = !c.health?.liveTradingArmed;
        try {
          await client.post('/admin/live-trading/arm', { armed: arm });
          toast('Live trading ' + (arm ? 'ARMED' : 'disarmed'), 'success');
          setTimeout(function () { loadPage('dashboard'); }, 1000);
        } catch (e) { toast('Error: ' + e.message, 'error'); }
      };
    }

    /* User action buttons (delegated) */
    document.querySelectorAll('.toggle-btn').forEach(function (btn) {
      btn.onclick = async function () {
        var id = btn.dataset.id;
        if (btn.dataset.action === 'toggle-user') {
          var enabled = btn.textContent.trim() === 'Disable';
          showModal('Confirm', 'Are you sure you want to ' + (enabled ? 'disable' : 'enable') + ' this user?', async function () {
            try { await actions.toggleUser(id, enabled); setTimeout(function () { loadPage('users'); }, 800); } catch (e) { toast('Error: ' + e.message, 'error'); }
          }, enabled ? 'Disable' : 'Enable', enabled);
        }
        if (btn.dataset.action === 'live-trade') {
          var approve = btn.textContent.trim() === 'Approve Live';
          showModal('Confirm Live Trading', 'Are you sure you want to ' + (approve ? 'approve' : 'revoke') + ' live trading for this user?', async function () {
            try { await actions.toggleLiveTrading(id, approve); setTimeout(function () { loadPage('users'); }, 800); } catch (e) { toast('Error: ' + e.message, 'error'); }
          }, approve ? 'Approve' : 'Revoke', approve);
        }
        if (btn.dataset.action === 'reset-pw') {
          showModal('Reset Password', 'This will generate a temporary password for the user. Proceed?', async function () {
            try { await actions.resetPassword(id); setTimeout(function () { loadPage('users'); }, 800); } catch (e) { toast('Error: ' + e.message, 'error'); }
          }, 'Reset Password');
        }
      };
    });

    /* Strategy toggle buttons */
    document.querySelectorAll('.strat-toggle').forEach(function (btn) {
      btn.onclick = async function () {
        var key = btn.dataset.key;
        var enabled = btn.dataset.enabled === 'true';
        showModal('Toggle Strategy', 'Are you sure you want to ' + (enabled ? 'disable' : 'enable') + ' strategy ' + key + '?', async function () {
          try { await actions.toggleStrategy(key, !enabled); setTimeout(function () { loadPage('strategies'); }, 800); } catch (e) { toast('Error: ' + e.message, 'error'); }
        }, enabled ? 'Disable' : 'Enable', enabled);
      };
    });

    /* Acknowledge alert buttons */
    document.querySelectorAll('.ack-alert').forEach(function (btn) {
      btn.onclick = async function () {
        var id = btn.dataset.id;
        if (!id) { toast('Alert ID not available', 'error'); return; }
        try {
          await actions.acknowledgeAlert(id);
          btn.textContent = 'Done';
          btn.style.background = '#999';
          btn.disabled = true;
          toast('Alert acknowledged', 'success');
        } catch (e) { toast('Error: ' + e.message, 'error'); }
      };
    });

    /* Backfill outcomes button */
    var bfBtn = document.getElementById('backfillOutcomesBtn');
    if (bfBtn) {
      bfBtn.onclick = async function () {
        bfBtn.textContent = 'Running...';
        bfBtn.disabled = true;
        try {
          await client.post('/admin/signals/track-outcomes');
          toast('Outcome backfill initiated', 'success');
          setTimeout(function () { loadPage('signal-monitor'); }, 1500);
        } catch (e) { toast('Error: ' + e.message, 'error'); }
        bfBtn.textContent = 'Backfill Outcomes';
        bfBtn.disabled = false;
      };
    }

    var bfExitBtn = document.getElementById('backfillExitsBtn');
    if (bfExitBtn) {
      bfExitBtn.onclick = async function () {
        bfExitBtn.textContent = 'Running...';
        bfExitBtn.disabled = true;
        try {
          await client.post('/admin/signals/backfill-outcome-exits');
          toast('Exit backfill initiated', 'success');
        } catch (e) { toast('Error: ' + e.message, 'error'); }
        bfExitBtn.textContent = 'Backfill Exit Orders';
        bfExitBtn.disabled = false;
      };
    }

    /* Stuck orders expire button */
    var expBtn = document.getElementById('expireStuckBtn');
    if (expBtn) {
      expBtn.onclick = async function () {
        expBtn.textContent = 'Expiring...';
        expBtn.disabled = true;
        try {
          await client.post('/admin/oms/stuck-orders/expire');
          toast('Stuck orders expired', 'success');
        } catch (e) { toast('Error: ' + e.message, 'error'); }
        expBtn.textContent = 'Expire Stuck Orders';
        expBtn.disabled = false;
      };
    }

    /* Position reconciliation buttons */
    var reconBtn = document.getElementById('runReconBtn');
    if (reconBtn) {
      reconBtn.onclick = async function () {
        reconBtn.textContent = 'Running...';
        reconBtn.disabled = true;
        try {
          var data = await client.get('/admin/oms/position-reconciliation');
          toast('Reconciliation data loaded', 'success');
        } catch (e) { toast('Error: ' + e.message, 'error'); }
        reconBtn.textContent = 'Run Reconciliation';
        reconBtn.disabled = false;
      };
    }

    var ghostBtn = document.getElementById('clearGhostsBtn');
    if (ghostBtn) {
      ghostBtn.onclick = async function () {
        ghostBtn.textContent = 'Clearing...';
        ghostBtn.disabled = true;
        try {
          await client.post('/admin/oms/position-reconciliation/clear-ghosts');
          toast('Ghost positions cleared', 'success');
        } catch (e) { toast('Error: ' + e.message, 'error'); }
        ghostBtn.textContent = 'Clear Ghost Positions';
        ghostBtn.disabled = false;
      };
    }

    /* Health fix button */
    var fixBtn = document.getElementById('fixAllBtn');
    if (fixBtn) {
      fixBtn.onclick = async function () {
        fixBtn.textContent = 'Fixing...';
        fixBtn.disabled = true;
        try {
          await client.post('/admin/oms/health/fix-all');
          toast('All health fixes applied', 'success');
        } catch (e) { toast('Error: ' + e.message, 'error'); }
        fixBtn.textContent = 'Fix All';
        fixBtn.disabled = false;
      };
    }

    /* Live logs SSE stream */
    if (page === 'live-logs') {
      var logContainer = document.getElementById('logContainer');
      if (logContainer) {
        logContainer.textContent = 'Connecting to log stream (SSE)...';
        var token = client.getToken();
        if (token) {
          var eventSource = new EventSource(CONFIG.API_BASE + '/admin/logs/stream?token=' + encodeURIComponent(token));
          eventSource.onmessage = function (evt) {
            try {
              var data = JSON.parse(evt.data);
              var line = (data.timestamp ? fmt.time(data.timestamp) + ' ' : '') +
                (data.level || 'INFO') + ' [' + (data.thread || '') + '] ' +
                (data.logger || '') + ' - ' + (data.message || '');
              logContainer.textContent += '\n' + line;
              logContainer.scrollTop = logContainer.scrollHeight;
              if (logContainer.textContent.length > 50000) {
                logContainer.textContent = logContainer.textContent.slice(-25000);
              }
            } catch (_) { }
          };
          eventSource.onerror = function () {
            logContainer.textContent += '\n--- SSE disconnected, reconnecting ---';
          };
        } else {
          logContainer.textContent = 'No auth token available. Please log in.';
        }
      }
    }
  }

  /* ---- Exports ---- */
  global.StokrPanel = {
    CONFIG: CONFIG,
    createTraderApp: function () {
      return createPanelApp({
        role: 'trader',
        defaultPrincipal: 'vishnualgo@gmail.com',
        fetchData: fetchTraderData,
        renderPage: renderTraderPage,
        afterRender: function (page, cache, renderChart) {
          if (page === 'dashboard' && cache.__chart) {
            setTimeout(function () { renderChart('chartCanvas', cache.__chart.labels, cache.__chart.values, cache.__chart.label); }, 50);
          }
        },
      });
    },
    createAdminApp: function () {
      return createPanelApp({
        role: 'admin',
        defaultPrincipal: 'admin@stokr.local',
        fetchData: fetchAdminData,
        renderPage: renderAdminPage,
        afterRender: function (page, cache, renderChart, client, toast, showModal) {
          adminAfterRender(page, cache, renderChart, client, toast);
        },
      });
    },
  };
})(window);

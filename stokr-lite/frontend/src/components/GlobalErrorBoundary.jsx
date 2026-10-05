import React from 'react';

const RELOAD_KEY = 'stokr:chunkReloadAt';

/** A page's JS file could not be loaded — usually a tab still running the previous deploy. */
export function isChunkLoadError(err) {
  const msg = String(err?.message || err || '');
  return /dynamically imported module|Importing a module script failed|ChunkLoadError|Loading chunk .* failed|error loading dynamically imported module/i.test(msg);
}

/** Reload once to pick up the new build; returns false if we already tried in the last 30s. */
export function reloadForNewBuild() {
  try {
    const last = Number(sessionStorage.getItem(RELOAD_KEY) || 0);
    if (Date.now() - last < 30000) return false;
    sessionStorage.setItem(RELOAD_KEY, String(Date.now()));
  } catch { /* storage blocked: still reload */ }
  window.location.reload();
  return true;
}

/**
 * Catches render errors anywhere in the app so a crash shows a message instead of a blank page.
 * A failed page-chunk load (stale tab after a deploy) reloads the app once automatically.
 */
export default class GlobalErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null, reloading: false };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('App crashed:', error, info?.componentStack);
    if (isChunkLoadError(error) && reloadForNewBuild()) this.setState({ reloading: true });
  }

  render() {
    const { error, reloading } = this.state;
    if (!error) return this.props.children;
    const stale = isChunkLoadError(error);
    return (
      <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f8fafc', padding: 16 }}>
        <div style={{ maxWidth: 560, width: '100%', background: '#fff', border: '1px solid #e2e8f0', borderRadius: 16, padding: 24, boxShadow: '0 1px 3px rgba(0,0,0,.06)', fontFamily: 'system-ui, sans-serif' }}>
          <div style={{ fontSize: 18, fontWeight: 800, color: '#0f172a', marginBottom: 6 }}>
            {reloading ? 'Updating to the latest version…' : stale ? 'A new version of Stokr is available' : 'Something went wrong on this page'}
          </div>
          <div style={{ fontSize: 13, color: '#475569', marginBottom: 14 }}>
            {stale ? 'This tab was still running the previous version. Reload to continue.' : 'The rest of the app is fine. Reload, or go back to the dashboard.'}
          </div>
          {!stale && (
            <pre style={{ fontSize: 11, color: '#b91c1c', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 8, padding: 10, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 160, overflow: 'auto' }}>
              {String(error?.message || error)}
            </pre>
          )}
          <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
            <button onClick={() => window.location.reload()} style={{ padding: '8px 14px', borderRadius: 10, border: 'none', background: '#4f46e5', color: '#fff', fontWeight: 700, cursor: 'pointer' }}>Reload</button>
            <button onClick={() => { window.location.href = '/'; }} style={{ padding: '8px 14px', borderRadius: 10, border: '1px solid #cbd5e1', background: '#fff', color: '#334155', fontWeight: 700, cursor: 'pointer' }}>Dashboard</button>
          </div>
        </div>
      </div>
    );
  }
}

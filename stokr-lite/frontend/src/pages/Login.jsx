import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import client from '../api/client';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const { data } = await client.post('/api/auth/login', { email, password });
      localStorage.setItem('token', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken);
      navigate('/');
    } catch (err) {
      setError(err.response?.data?.error || 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-aurora" style={{ minHeight: '100vh', display: 'flex', position: 'relative', overflow: 'hidden' }}>
      {/* Blob background */}
      <div style={{ position: 'fixed', inset: 0, zIndex: 0, overflow: 'hidden', pointerEvents: 'none' }}>
        <div className="animate-blob-drift" style={{ width: '600px', height: '600px', background: 'linear-gradient(135deg, #a78bfa, #60a5fa)', top: '-200px', left: '-200px', position: 'absolute', borderRadius: '50%', filter: 'blur(120px)', opacity: 0.25 }} />
        <div className="animate-blob-drift" style={{ width: '500px', height: '500px', background: 'linear-gradient(135deg, #22d3ee, #10b981)', bottom: '-150px', right: '-150px', position: 'absolute', borderRadius: '50%', filter: 'blur(120px)', opacity: 0.25, animationDelay: '3s' }} />
        <div className="animate-blob-drift" style={{ width: '400px', height: '400px', background: 'linear-gradient(135deg, #f472b6, #f59e0b)', top: '40%', left: '30%', position: 'absolute', borderRadius: '50%', filter: 'blur(120px)', opacity: 0.15, animationDelay: '6s' }} />
      </div>

      {/* Left Panel - Branding */}
      <div className="hidden lg:flex" style={{ width: '50%', position: 'relative', zIndex: 1, alignItems: 'center', padding: '0 64px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '16px', marginBottom: '48px' }}>
            <div className="animate-brand-pop" style={{ width: '56px', height: '56px', borderRadius: '16px', background: 'linear-gradient(135deg, #6366f1 0%, #a78bfa 50%, #60a5fa 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '28px', fontWeight: 900, color: 'white', boxShadow: '0 12px 48px rgba(99,102,241,0.4)' }}>
              S
            </div>
            <div>
              <div style={{ fontSize: '28px', fontWeight: 800, background: 'linear-gradient(135deg, #0f172a, #4f46e5)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>Stokr</div>
              <div style={{ fontSize: '10px', fontWeight: 800, letterSpacing: '1.5px', color: '#94a3b8', textTransform: 'uppercase' }}>Aurora Pro</div>
            </div>
          </div>
          <h2 style={{ fontSize: '42px', fontWeight: 900, color: '#0f172a', lineHeight: 1.15, marginBottom: '16px', letterSpacing: '-1px' }}>
            Algorithmic Trading<br />Made Simple
          </h2>
          <p style={{ fontSize: '16px', color: '#64748b', maxWidth: '420px', lineHeight: 1.6 }}>
            Deploy strategies, connect brokers, and automate your trading with institutional-grade execution.
          </p>
          <div style={{ marginTop: '48px', display: 'flex', gap: '40px' }}>
            <div>
              <div style={{ fontSize: '28px', fontWeight: 900, color: '#4f46e5' }}>3+</div>
              <div style={{ fontSize: '11px', fontWeight: 700, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '1px', marginTop: '4px' }}>Strategies</div>
            </div>
            <div>
              <div style={{ fontSize: '28px', fontWeight: 900, color: '#4f46e5' }}>3</div>
              <div style={{ fontSize: '11px', fontWeight: 700, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '1px', marginTop: '4px' }}>Brokers</div>
            </div>
            <div>
              <div style={{ fontSize: '28px', fontWeight: 900, color: '#4f46e5' }}>30+</div>
              <div style={{ fontSize: '11px', fontWeight: 700, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '1px', marginTop: '4px' }}>NSE Stocks</div>
            </div>
          </div>
        </div>
      </div>

      {/* Right Panel - Form */}
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'relative', zIndex: 1, padding: '0 32px' }}>
        <div className="card-crystal" style={{ width: '100%', maxWidth: '420px', padding: '40px' }}>
          <div className="lg:hidden" style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '32px', justifyContent: 'center' }}>
            <div style={{ width: '44px', height: '44px', borderRadius: '14px', background: 'linear-gradient(135deg, #6366f1 0%, #a78bfa 50%, #60a5fa 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '22px', fontWeight: 900, color: 'white' }}>
              S
            </div>
            <span style={{ fontSize: '22px', fontWeight: 800, color: '#0f172a' }}>Stokr</span>
          </div>

          <h1 style={{ fontSize: '24px', fontWeight: 800, color: '#0f172a', marginBottom: '4px' }}>Welcome back</h1>
          <p style={{ color: '#94a3b8', marginBottom: '28px', fontSize: '14px' }}>Sign in to access your trading dashboard</p>

          {error && (
            <div style={{ background: 'rgba(239,68,68,0.08)', border: '2px solid rgba(239,68,68,0.15)', color: '#dc2626', fontSize: '13px', padding: '12px 16px', borderRadius: '12px', marginBottom: '20px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span>⚠️</span> {error}
            </div>
          )}

          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '13px', fontWeight: 600, color: '#475569', marginBottom: '6px' }}>Email</label>
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required
                placeholder="you@example.com"
                className="input-crystal" style={{ width: '100%' }} />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '13px', fontWeight: 600, color: '#475569', marginBottom: '6px' }}>Password</label>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required
                placeholder="Enter your password"
                className="input-crystal" style={{ width: '100%' }} />
            </div>
            <button type="submit" disabled={loading}
              style={{ width: '100%', background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: 'white', padding: '14px', borderRadius: '14px', fontWeight: 700, fontSize: '14px', border: 'none', cursor: 'pointer', transition: 'all 0.3s', boxShadow: '0 8px 24px rgba(99,102,241,0.3)', marginTop: '4px' }}
              onMouseEnter={(e) => { if (!loading) e.currentTarget.style.transform = 'translateY(-2px)'; }}
              onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; }}
            >
              {loading ? (
                <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}>
                  <span className="animate-spin" style={{ display: 'inline-block', width: '16px', height: '16px', border: '2px solid white', borderTopColor: 'transparent', borderRadius: '50%' }} />
                  Signing in...
                </span>
              ) : 'Sign In'}
            </button>
          </form>

          <p style={{ textAlign: 'center', fontSize: '13px', color: '#94a3b8', marginTop: '24px' }}>
            Don't have an account?{' '}
            <Link to="/register" style={{ color: '#4f46e5', fontWeight: 700, textDecoration: 'none' }}>Create account</Link>
          </p>
        </div>
      </div>
    </div>
  );
}


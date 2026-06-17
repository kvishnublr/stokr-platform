import { useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';

const BROKER_LABELS = {
  zerodha: 'Zerodha',
  dhan: 'Dhan',
  fyers: 'Fyers',
};

export default function BrokerCallback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const handled = useRef(false);

  const broker = searchParams.get('broker') || 'unknown';
  const status = searchParams.get('status');
  const reason = searchParams.get('reason') || '';
  const message = searchParams.get('message') || '';
  const isSuccess = status === 'ok';
  const brokerLabel = BROKER_LABELS[broker] || broker.charAt(0).toUpperCase() + broker.slice(1);

  useEffect(() => {
    if (handled.current) return;
    handled.current = true;

    // If this was opened as a popup, send message to parent and close
    if (window.opener) {
      window.opener.postMessage({
        type: 'stokr_broker_oauth',
        status: isSuccess ? 'ok' : 'error',
        broker,
        reason,
        message,
      }, '*');
      // Brief delay so message is sent before closing
      setTimeout(() => window.close(), 300);
      return;
    }

    // If opened as a full tab (no opener), auto-redirect to /brokers after showing result
    const timer = setTimeout(() => {
      navigate('/brokers', { replace: true });
    }, 3000);

    return () => clearTimeout(timer);
  }, [isSuccess, broker, reason, message, navigate]);

  // Also invalidate broker queries on success so the Brokers page shows updated status
  useEffect(() => {
    if (isSuccess) {
      queryClient.invalidateQueries({ queryKey: ['brokers'] });
    }
  }, [isSuccess, queryClient]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 px-4">
      <div className="max-w-md w-full text-center">
        {isSuccess ? (
          <>
            <div className="w-16 h-16 rounded-full bg-emerald-100 flex items-center justify-center mx-auto mb-5">
              <svg className="w-8 h-8 text-emerald-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-slate-800 mb-2">{brokerLabel} connected!</h1>
            <p className="text-slate-500 mb-6">Your broker account has been linked successfully.</p>
          </>
        ) : (
          <>
            <div className="w-16 h-16 rounded-full bg-rose-100 flex items-center justify-center mx-auto mb-5">
              <svg className="w-8 h-8 text-rose-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-slate-800 mb-2">{brokerLabel} linking failed</h1>
            <p className="text-slate-400 text-sm mb-2">{reason.replace(/_/g, ' ')}</p>
            {message && <p className="text-slate-500 text-sm mb-6">{decodeURIComponent(message)}</p>}
          </>
        )}

        {window.opener ? (
          <button
            onClick={() => window.close()}
            className="inline-flex items-center gap-2 bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-6 py-2.5 rounded-xl text-sm font-medium hover:from-indigo-700 hover:to-violet-700 transition shadow-lg shadow-indigo-500/20"
          >
            Close this window
          </button>
        ) : (
          <p className="text-slate-400 text-sm">
            Redirecting to Brokers page...
          </p>
        )}
      </div>
    </div>
  );
}

import React, { useEffect, useMemo, useRef, useState } from 'react';

const LOT = { NIFTY: 25, BANKNIFTY: 15, FINNIFTY: 25, MIDCPNIFTY: 50 };

function isConversion(action) {
  const a = String(action || '').toUpperCase();
  return a.includes('CONVERSION') || (a.includes('BUY CE') && a.includes('SELL PE'));
}

/** Expiry P&amp;L for 1 set (CE+PE+FUT), ₹. Nearly flat for true parity hedge. */
export function buildParityPayoff(opp, lots = 1) {
  const K = Number(opp.strike) || 0;
  const F = Number(opp.futuresPrice) || Number(opp.spotPrice) || K;
  const spot = Number(opp.spotPrice) || F;
  const u = String(opp.underlying || 'NIFTY').toUpperCase();
  const lotSize = Number(opp.lotSize) || LOT[u] || 25;
  const mult = lotSize * Math.max(1, lots);
  const conversion = isConversion(opp.action);

  const ceAsk = Number(opp.ceAsk ?? opp.cePrice) || 0;
  const ceBid = Number(opp.ceBid ?? opp.cePrice) || 0;
  const peAsk = Number(opp.peAsk ?? opp.pePrice) || 0;
  const peBid = Number(opp.peBid ?? opp.pePrice) || 0;
  const ceEntry = conversion ? ceAsk : ceBid;
  const peEntry = conversion ? peBid : peAsk;

  const range = Math.max(K * 0.06, (LOT[u] === 15 ? 800 : 400));
  const minS = Math.round((spot - range) / 10) * 10;
  const maxS = Math.round((spot + range) / 10) * 10;
  const step = Math.max(5, Math.round((maxS - minS) / 80 / 5) * 5);

  const points = [];
  for (let s = minS; s <= maxS; s += step) {
    const ce = Math.max(s - K, 0);
    const pe = Math.max(K - s, 0);
    let cePnl; let pePnl; let futPnl;
    if (conversion) {
      cePnl = (ce - ceEntry) * mult;
      pePnl = (peEntry - pe) * mult;
      futPnl = (F - s) * mult;
    } else {
      cePnl = (ceEntry - ce) * mult;
      pePnl = (pe - peEntry) * mult;
      futPnl = (s - F) * mult;
    }
    const total = cePnl + pePnl + futPnl;
    points.push({ s, total, cePnl, pePnl, futPnl });
  }

  const totals = points.map(p => p.total);
  const maxProfit = Math.max(...totals);
  const maxLoss = Math.min(...totals);
  // Theoretical locked edge (constant term)
  const lockedPts = conversion
    ? (F - K - ceEntry + peEntry)
    : (ceEntry - peEntry - F + K);
  const lockedInr = lockedPts * mult;
  const netEdge = Number(opp.edgeAfterCosts) || lockedInr;

  return {
    points,
    minS,
    maxS,
    spot,
    strike: K,
    fut: F,
    conversion,
    lotSize,
    lots: Math.max(1, lots),
    mult,
    maxProfit,
    maxLoss,
    lockedInr,
    netEdge,
    ceEntry,
    peEntry,
    legs: conversion
      ? [`BUY ${K} CE @ ${ceEntry}`, `SELL ${K} PE @ ${peEntry}`, `SELL FUT @ ${F}`]
      : [`SELL ${K} CE @ ${ceEntry}`, `BUY ${K} PE @ ${peEntry}`, `BUY FUT @ ${F}`],
  };
}

function money(v) {
  const n = Math.round(Number(v) || 0);
  const sign = n > 0 ? '+' : '';
  return `${sign}₹${Math.abs(n).toLocaleString('en-IN')}`;
}

/**
 * Opstra-style animated payoff for Bid Parity conversion / reversal.
 */
export default function BidParityPayoffChart({
  opp,
  lots = 1,
  onPaperTrade,
  executionBroker = 'PAPER',
}) {
  const model = useMemo(() => buildParityPayoff(opp, lots), [opp, lots]);
  const [hover, setHover] = useState(null);
  const [drawn, setDrawn] = useState(false);
  const svgRef = useRef(null);

  useEffect(() => {
    setDrawn(false);
    const t = requestAnimationFrame(() => setDrawn(true));
    return () => cancelAnimationFrame(t);
  }, [opp?.strike, opp?.action, opp?.expiryDate, lots]);

  const W = 720;
  const H = 320;
  const pad = { l: 56, r: 24, t: 28, b: 40 };
  const iw = W - pad.l - pad.r;
  const ih = H - pad.t - pad.b;

  const yMin = Math.min(model.maxLoss, 0) - Math.abs(model.maxProfit - model.maxLoss) * 0.15;
  const yMax = Math.max(model.maxProfit, 0) + Math.abs(model.maxProfit - model.maxLoss) * 0.2;
  const x = (s) => pad.l + ((s - model.minS) / Math.max(1, model.maxS - model.minS)) * iw;
  const y = (pnl) => pad.t + ((yMax - pnl) / Math.max(1e-6, yMax - yMin)) * ih;

  const pathFor = (key) =>
    model.points
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.s).toFixed(1)},${y(p[key]).toFixed(1)}`)
      .join(' ');

  const totalPath = pathFor('total');
  const zeroY = y(0);
  const spotX = x(model.spot);
  const strikeX = x(model.strike);

  // Area under total curve vs zero
  const areaD = (() => {
    if (!model.points.length) return '';
    let d = `M${x(model.points[0].s)},${zeroY}`;
    model.points.forEach((p) => { d += ` L${x(p.s)},${y(p.total)}`; });
    d += ` L${x(model.points[model.points.length - 1].s)},${zeroY} Z`;
    return d;
  })();

  const onMove = (e) => {
    const rect = svgRef.current?.getBoundingClientRect();
    if (!rect) return;
    const px = ((e.clientX - rect.left) / rect.width) * W;
    const s = model.minS + ((px - pad.l) / iw) * (model.maxS - model.minS);
    let best = model.points[0];
    let bestD = Infinity;
    for (const p of model.points) {
      const d = Math.abs(p.s - s);
      if (d < bestD) { bestD = d; best = p; }
    }
    setHover(best);
  };

  const weekly = opp.expiryMode === 'WEEKLY' || opp.basisRisk || opp.strategyType === 'BID_PARITY_WEEKLY';

  return (
    <div className="bp-payoff animate-[fadeInUp_0.45s_ease-out]">
      <div className="relative overflow-hidden rounded-2xl border border-slate-200/80 bg-gradient-to-br from-slate-950 via-slate-900 to-amber-950 text-white shadow-xl">
        {/* soft orbs */}
        <div className="pointer-events-none absolute -left-16 -top-20 h-56 w-56 rounded-full bg-amber-500/20 blur-3xl bp-orb" />
        <div className="pointer-events-none absolute -right-10 bottom-0 h-48 w-48 rounded-full bg-emerald-400/15 blur-3xl bp-orb-delay" />

        <div className="relative p-4 md:p-5 space-y-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <div className="text-[10px] font-bold uppercase tracking-[0.2em] text-amber-300/80">
                Expiry Payoff · Opstra-style
              </div>
              <h3 className="text-lg font-black tracking-tight mt-0.5">
                {opp.underlying} {model.strike}{' '}
                <span className="text-amber-300">{model.conversion ? 'CONVERSION' : 'REVERSAL'}</span>
              </h3>
              <p className="text-[11px] text-slate-300 mt-1 font-medium">
                {model.legs.join(' · ')} · lot {model.lotSize} × {model.lots}
                {weekly ? ' · weekly basis residual' : ''}
              </p>
            </div>
            <div className="flex flex-wrap gap-2">
              <StatPill label="Max profit" value={money(model.maxProfit)} tone="good" />
              <StatPill label="Max loss" value={money(model.maxLoss)} tone={model.maxLoss < -50 ? 'bad' : 'muted'} />
              <StatPill label="Net edge" value={money(model.netEdge)} tone="accent" />
              <StatPill label="Locked ≈" value={money(model.lockedInr)} tone="muted" />
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-[1fr_200px] gap-4 items-stretch">
            <div className="rounded-xl bg-slate-950/60 border border-white/10 p-2 backdrop-blur-sm">
              <svg
                ref={svgRef}
                viewBox={`0 0 ${W} ${H}`}
                className="w-full h-auto select-none"
                onMouseMove={onMove}
                onMouseLeave={() => setHover(null)}
              >
                <defs>
                  <linearGradient id="bpProfitFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#34d399" stopOpacity="0.45" />
                    <stop offset="100%" stopColor="#34d399" stopOpacity="0.02" />
                  </linearGradient>
                  <linearGradient id="bpStroke" x1="0" y1="0" x2="1" y2="0">
                    <stop offset="0%" stopColor="#fbbf24" />
                    <stop offset="50%" stopColor="#34d399" />
                    <stop offset="100%" stopColor="#38bdf8" />
                  </linearGradient>
                  <filter id="bpGlow" x="-20%" y="-20%" width="140%" height="140%">
                    <feGaussianBlur stdDeviation="3" result="b" />
                    <feMerge>
                      <feMergeNode in="b" />
                      <feMergeNode in="SourceGraphic" />
                    </feMerge>
                  </filter>
                </defs>

                {/* grid */}
                {[0.25, 0.5, 0.75].map((t) => (
                  <line
                    key={t}
                    x1={pad.l}
                    x2={W - pad.r}
                    y1={pad.t + ih * t}
                    y2={pad.t + ih * t}
                    stroke="rgba(148,163,184,0.15)"
                    strokeDasharray="4 6"
                  />
                ))}

                {/* zero line */}
                <line
                  x1={pad.l}
                  x2={W - pad.r}
                  y1={zeroY}
                  y2={zeroY}
                  stroke="rgba(248,250,252,0.35)"
                  strokeWidth="1.25"
                />

                {/* strike + spot guides */}
                <line x1={strikeX} x2={strikeX} y1={pad.t} y2={H - pad.b} stroke="rgba(167,139,250,0.45)" strokeDasharray="3 5" />
                <line x1={spotX} x2={spotX} y1={pad.t} y2={H - pad.b} stroke="rgba(251,191,36,0.55)" strokeDasharray="2 4" />
                <text x={strikeX + 4} y={pad.t + 12} fill="#c4b5fd" fontSize="10" fontWeight="700">K {model.strike}</text>
                <text x={spotX + 4} y={pad.t + 24} fill="#fcd34d" fontSize="10" fontWeight="700">Spot {Math.round(model.spot)}</text>

                {/* profit/loss area */}
                <path
                  d={areaD}
                  fill="url(#bpProfitFill)"
                  className={drawn ? 'bp-area-in' : 'opacity-0'}
                />

                {/* leg ghosts */}
                <path d={pathFor('cePnl')} fill="none" stroke="#38bdf8" strokeWidth="1.2" strokeOpacity="0.35" strokeDasharray="4 4" className={drawn ? 'bp-line-draw' : ''} />
                <path d={pathFor('pePnl')} fill="none" stroke="#f472b6" strokeWidth="1.2" strokeOpacity="0.35" strokeDasharray="4 4" className={drawn ? 'bp-line-draw' : ''} style={{ animationDelay: '0.08s' }} />
                <path d={pathFor('futPnl')} fill="none" stroke="#a78bfa" strokeWidth="1.2" strokeOpacity="0.35" strokeDasharray="4 4" className={drawn ? 'bp-line-draw' : ''} style={{ animationDelay: '0.16s' }} />

                {/* main payoff */}
                <path
                  d={totalPath}
                  fill="none"
                  stroke="url(#bpStroke)"
                  strokeWidth="3.2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  filter="url(#bpGlow)"
                  className={drawn ? 'bp-line-draw' : ''}
                  style={{ animationDelay: '0.12s' }}
                />

                {/* max profit marker */}
                {(() => {
                  const peak = model.points.reduce((a, b) => (b.total >= a.total ? b : a));
                  return (
                    <g className={drawn ? 'bp-pop' : 'opacity-0'}>
                      <circle cx={x(peak.s)} cy={y(peak.total)} r="5" fill="#34d399" />
                      <circle cx={x(peak.s)} cy={y(peak.total)} r="10" fill="#34d399" opacity="0.25" className="bp-pulse" />
                      <text x={x(peak.s)} y={y(peak.total) - 12} textAnchor="middle" fill="#6ee7b7" fontSize="11" fontWeight="800">
                        MAX {money(peak.total)}
                      </text>
                    </g>
                  );
                })()}

                {/* hover crosshair */}
                {hover && (
                  <g>
                    <line x1={x(hover.s)} x2={x(hover.s)} y1={pad.t} y2={H - pad.b} stroke="rgba(255,255,255,0.35)" />
                    <circle cx={x(hover.s)} cy={y(hover.total)} r="6" fill="#fbbf24" stroke="#fff" strokeWidth="2" />
                    <rect
                      x={Math.min(W - 150, Math.max(pad.l, x(hover.s) - 60))}
                      y={Math.max(pad.t, y(hover.total) - 48)}
                      width="120"
                      height="36"
                      rx="8"
                      fill="rgba(15,23,42,0.92)"
                      stroke="rgba(251,191,36,0.5)"
                    />
                    <text
                      x={Math.min(W - 150, Math.max(pad.l, x(hover.s) - 60)) + 60}
                      y={Math.max(pad.t, y(hover.total) - 48) + 15}
                      textAnchor="middle"
                      fill="#f8fafc"
                      fontSize="10"
                      fontWeight="700"
                    >
                      Spot {Math.round(hover.s)}
                    </text>
                    <text
                      x={Math.min(W - 150, Math.max(pad.l, x(hover.s) - 60)) + 60}
                      y={Math.max(pad.t, y(hover.total) - 48) + 28}
                      textAnchor="middle"
                      fill={hover.total >= 0 ? '#34d399' : '#f87171'}
                      fontSize="11"
                      fontWeight="800"
                    >
                      {money(hover.total)}
                    </text>
                  </g>
                )}

                {/* axes labels */}
                <text x={pad.l} y={H - 12} fill="#94a3b8" fontSize="10">{Math.round(model.minS)}</text>
                <text x={W - pad.r} y={H - 12} fill="#94a3b8" fontSize="10" textAnchor="end">{Math.round(model.maxS)}</text>
                <text x={12} y={pad.t + 8} fill="#94a3b8" fontSize="10">P&amp;L ₹</text>
                <text x={12} y={zeroY + 3} fill="#cbd5e1" fontSize="10">0</text>
              </svg>

              <div className="flex flex-wrap gap-3 px-2 pb-1 text-[10px] font-bold text-slate-400">
                <span className="inline-flex items-center gap-1"><i className="h-0.5 w-4 rounded bg-gradient-to-r from-amber-400 to-emerald-400 inline-block" /> Combined</span>
                <span className="text-sky-400/80">– – CE</span>
                <span className="text-pink-400/80">– – PE</span>
                <span className="text-violet-400/80">– – FUT</span>
                <span className="ml-auto text-slate-500 font-medium">Hover to scrub spot → P&amp;L</span>
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <div className="rounded-xl border border-white/10 bg-white/5 p-3 space-y-2 backdrop-blur">
                <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">At expiry</div>
                <Row k="If all 3 legs fill clean" v="≈ flat locked edge" />
                <Row k="Max profit" v={money(model.maxProfit)} good />
                <Row k="Worst on chart" v={money(model.maxLoss)} />
                <Row k="Breakeven" v={Math.abs(model.maxLoss) < 80 && model.maxProfit > 0 ? 'Edge locked (all spots)' : 'See curve'} />
                <p className="text-[10px] text-slate-400 leading-relaxed pt-1">
                  True conversion/reversal payoff is nearly <span className="text-emerald-300 font-bold">horizontal</span> —
                  max profit ≈ captured edge. Slip / partial fill can tilt this.
                </p>
              </div>

              {onPaperTrade && (
                <button
                  type="button"
                  onClick={(e) => { e.stopPropagation(); onPaperTrade(opp, model.lots); }}
                  className="bp-cta group relative overflow-hidden rounded-xl bg-gradient-to-r from-amber-500 via-orange-500 to-amber-600 px-4 py-3 text-sm font-black text-slate-950 shadow-lg shadow-amber-900/40 transition hover:scale-[1.02] active:scale-[0.99]"
                >
                  <span className="relative z-10">📝 Paper trade this setup</span>
                  <span className="absolute inset-0 translate-x-[-100%] bg-white/25 skew-x-[-20deg] group-hover:animate-[bpShine_0.8s_ease]" />
                  <div className="relative z-10 text-[10px] font-bold text-slate-900/70 mt-0.5">
                    via {executionBroker} · {model.conversion ? 'Conversion' : 'Reversal'}
                  </div>
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function StatPill({ label, value, tone }) {
  const tones = {
    good: 'border-emerald-400/40 bg-emerald-500/15 text-emerald-200',
    bad: 'border-rose-400/40 bg-rose-500/15 text-rose-200',
    accent: 'border-amber-400/40 bg-amber-500/15 text-amber-100',
    muted: 'border-white/10 bg-white/5 text-slate-200',
  };
  return (
    <div className={`rounded-xl border px-3 py-1.5 ${tones[tone] || tones.muted}`}>
      <div className="text-[9px] font-bold uppercase tracking-wider opacity-70">{label}</div>
      <div className="text-sm font-black font-mono">{value}</div>
    </div>
  );
}

function Row({ k, v, good }) {
  return (
    <div className="flex items-center justify-between gap-2 text-[11px]">
      <span className="text-slate-400 font-semibold">{k}</span>
      <span className={`font-bold font-mono ${good ? 'text-emerald-300' : 'text-slate-100'}`}>{v}</span>
    </div>
  );
}

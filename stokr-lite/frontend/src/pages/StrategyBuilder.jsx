import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';
import { useGlobalExecutionBroker } from '../context/ExecutionBrokerContext';
import { XAxis, YAxis, CartesianGrid, Tooltip, ReferenceLine, ResponsiveContainer, AreaChart, Area } from 'recharts';

export default function StrategyBuilder() {
  const [underlying, setUnderlying] = useState('NIFTY');
  const [expiry, setExpiry] = useState('');
  const [legs, setLegs] = useState([]);
    const { executionBroker } = useGlobalExecutionBroker();
  const [showDeployModal, setShowDeployModal] = useState(false);
    const [deployName, setDeployName] = useState('');
  const [deploySuccessMsg, setDeploySuccessMsg] = useState(null);
  
  
  const [lots, setLots] = useState(1);
  const chainContainerRef = useRef(null);

  // Fetch available expiry dates from backend
  const { data: availableExpiries } = useQuery({
    queryKey: ['availableExpiries', underlying],
    queryFn: async () => {
      const res = await client.get(`/option-arbitrage/available-expiries?underlying=${underlying}`);
      return res.data;
    },
    staleTime: 60000,
  });
  const generatedExpiries = availableExpiries || [];

  const { data: chainData, isLoading: chainLoading } = useQuery({
    queryKey: ['optionChain', underlying, expiry],
    queryFn: async () => {
      const res = await client.get(`/option-arbitrage/option-chain?underlying=${underlying}${expiry ? '&expiry='+expiry : ''}`);
      return res.data;
    },
    refetchInterval: 5000,
  });

  // Calculate Payoff Data
  const payoffData = useMemo(() => {
    if (!chainData || !chainData.spotPrice || legs.length === 0) return [];
    
    const spot = chainData.spotPrice;
    const lotSize = chainData.lotSize || ({ NIFTY: 75, BANKNIFTY: 30, FINNIFTY: 40, MIDCPNIFTY: 50, SENSEX: 20, BANKEX: 30 }[underlying] || 50);
    const actualLots = Math.max(1, lots);

    // Dynamic range based on furthest strikes
    let minStrike = spot;
    let maxStrike = spot;
    legs.forEach(l => {
        if (l.strike < minStrike) minStrike = l.strike;
        if (l.strike > maxStrike) maxStrike = l.strike;
    });
    
    const strikeRange = maxStrike - minStrike;
    const padding = Math.max(spot * 0.025, strikeRange * 0.6);
    const minSpot = Math.min(spot, minStrike) - padding;
    const maxSpot = Math.max(spot, maxStrike) + padding;
    const step = (maxSpot - minSpot) / 100;

    const data = [];
    for (let currentSpot = minSpot; currentSpot <= maxSpot; currentSpot += step) {
      let pnl = 0;
      legs.forEach(leg => {
        const isCall = leg.optionType === 'CE';
        const strike = leg.strike;
        
        let intrinsicValue = 0;
        if (isCall) {
          intrinsicValue = Math.max(0, currentSpot - strike);
        } else {
          intrinsicValue = Math.max(0, strike - currentSpot);
        }

        const legPnl = (intrinsicValue - leg.price) * lotSize * actualLots * leg.qty * (leg.side === 'BUY' ? 1 : -1);
        pnl += legPnl;
      });
      
      data.push({
        spot: Math.round(currentSpot),
        pnl: Math.round(pnl)
      });
    }
    return data;
  }, [chainData, legs, lots, underlying]);

  const metrics = useMemo(() => {
    if (payoffData.length === 0) return null;
    let maxP = -Infinity;
    let maxL = Infinity;
    
    payoffData.forEach(d => {
      if (d.pnl > maxP) maxP = d.pnl;
      if (d.pnl < maxL) maxL = d.pnl;
    });

    let netPremium = 0;
    const lotSize = chainData?.lotSize || (underlying === 'NIFTY' ? 75 : (underlying === 'BANKNIFTY' ? 30 : 40));
    legs.forEach(l => {
      const val = l.price * lotSize * Math.max(1, lots) * l.qty;
      if (l.side === 'BUY') netPremium -= val;
      else netPremium += val;
    });

        let breakevens = [];
    for (let i = 1; i < payoffData.length; i++) {
        if ((payoffData[i-1].pnl <= 0 && payoffData[i].pnl > 0) || (payoffData[i-1].pnl >= 0 && payoffData[i].pnl < 0)) {
            breakevens.push(payoffData[i].spot);
        }
    }
    
    let breakevenText = '-';
    if (breakevens.length === 1) {
        breakevenText = breakevens[0].toLocaleString();
    } else if (breakevens.length >= 2) {
        breakevenText = `${breakevens[0].toLocaleString()} - ${breakevens[breakevens.length-1].toLocaleString()}`;
    }

    return {
      maxProfit: maxP > 100000 ? 'Unlimited' : maxP,
      maxLoss: maxL < -100000 ? 'Unlimited' : maxL,
      netPremium,
      breakevenText,
      breakevens
    };
  }, [payoffData, legs, lots, underlying]);

  // Gradient offset to split green/red perfectly at 0 line
  const gradientOffset = () => {
    if (payoffData.length === 0) return 0;
    const dataMax = Math.max(...payoffData.map(i => i.pnl));
    const dataMin = Math.min(...payoffData.map(i => i.pnl));
    if (dataMax <= 0) return 0;
    if (dataMin >= 0) return 1;
    return dataMax / (dataMax - dataMin);
  };
  const off = gradientOffset();

  const addLeg = (strike, optionType, side, price) => {
    if (!price) return;
    setLegs(prev => {
      const existing = prev.find(l => l.strike === strike && l.optionType === optionType && l.side === side);
      if (existing) {
        return prev.map(l => l.id === existing.id ? { ...l, qty: l.qty + 1 } : l);
      }
      return [...prev, {
        id: Math.random().toString(36).substr(2, 9),
        strike,
        optionType,
        side,
        price,
        qty: 1
      }];
    });
  };

  const removeLeg = (id) => setLegs(prev => prev.filter(l => l.id !== id));
  
  const toggleLegSide = (id) => {
    setLegs(prev => prev.map(l => l.id === id ? { ...l, side: l.side === 'BUY' ? 'SELL' : 'BUY' } : l));
  };
  
  const reverseStrategy = () => {
    setLegs(prev => prev.map(l => ({ ...l, side: l.side === 'BUY' ? 'SELL' : 'BUY' })));
  };

  const clearAll = () => setLegs([]);

  // Auto scroll to ATM on load
  const scrollToATM = () => {
    if (chainContainerRef.current) {
        const atmRow = chainContainerRef.current.querySelector('.atm-row');
        if (atmRow) {
            atmRow.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    }
  };

  useEffect(() => {
    if (chainData?.chain?.length > 0) {
        setTimeout(scrollToATM, 300); 
    }
  }, [chainData?.expiry]);

  // Prebuilt Strategies
  const buildStrategy = (type) => {
    if (!chainData || !chainData.chain) return;
    const spot = chainData.spotPrice;
    
    // Find ATM index
    let atmIndex = 0;
    let minDiff = Infinity;
    chainData.chain.forEach((row, idx) => {
        const diff = Math.abs(row.strike - spot);
        if (diff < minDiff) {
            minDiff = diff;
            atmIndex = idx;
        }
    });

    const getRow = (offset) => chainData.chain[atmIndex + offset];
    if (!getRow(0)) return;

    const newLegs = [];
    const pushLeg = (row, optType, side) => {
        if (!row) return;
        const price = optType === 'CE' ? row.ceLtp : row.peLtp;
        if (price) {
            newLegs.push({
                id: Math.random().toString(36).substr(2, 9),
                strike: row.strike, optionType: optType, side, price, qty: 1
            });
        }
    };

    if (type === 'STRADDLE') {
        pushLeg(getRow(0), 'CE', 'BUY');
        pushLeg(getRow(0), 'PE', 'BUY');
    } else if (type === 'STRANGLE') {
        pushLeg(getRow(2), 'CE', 'BUY'); 
        pushLeg(getRow(-2), 'PE', 'BUY'); 
    } else if (type === 'BULL_CALL') {
        pushLeg(getRow(0), 'CE', 'BUY');
        pushLeg(getRow(1), 'CE', 'SELL');
    } else if (type === 'BEAR_PUT') {
        pushLeg(getRow(0), 'PE', 'BUY');
        pushLeg(getRow(-1), 'PE', 'SELL');
    } else if (type === 'IRON_CONDOR') {
        pushLeg(getRow(1), 'CE', 'SELL');
        pushLeg(getRow(2), 'CE', 'BUY');
        pushLeg(getRow(-1), 'PE', 'SELL');
        pushLeg(getRow(-2), 'PE', 'BUY');
    } else if (type === 'IRON_FLY') {
        pushLeg(getRow(0), 'CE', 'SELL');
        pushLeg(getRow(0), 'PE', 'SELL');
        pushLeg(getRow(1), 'CE', 'BUY');
        pushLeg(getRow(-1), 'PE', 'BUY');
    }
    
    setLegs(newLegs);
  };

  const deployStrategy = () => {
    if (legs.length === 0) return alert('Add legs to your strategy first');
    
    let defaultName = `Custom ${underlying} Strategy`;
    if (legs.length === 2 && legs[0].strike === legs[1].strike) {
        defaultName = `${underlying} Straddle`;
    } else if (legs.length === 4) {
        defaultName = `${underlying} Iron Condor/Fly`;
    }
    
    setDeployName(defaultName);
    setShowDeployModal(true);
  };
  
  const confirmDeploy = async (e) => {
    e.preventDefault();
    setShowDeployModal(false);
    if (!deployName) return;

    try {
      const legList = legs.map(l => ({
        strike: l.strike, optionType: l.optionType, side: l.side, qty: l.qty, price: l.price
      }));
      
      const endpoint = '/option-arbitrage/paper-trade/execute';
      
      const res = await client.post(endpoint, {
        underlying, strategyType: 'CUSTOM_BUILDER', legList, description: deployName, lots, broker: executionBroker, executionMode: executionBroker === 'PAPER' ? 'PAPER' : 'LIVE'
      });
      if (res.data?.status === 'SUCCESS' || res.data?.status === 'SUBMITTED' || res.data?.status === 'RUNNING') {
        setDeploySuccessMsg({ type: 'success', text: `✨ Your strategy "${deployName}" has been successfully deployed.` });
        setTimeout(() => setDeploySuccessMsg(null), 5000);
      } else {
        setDeploySuccessMsg({ type: 'error', text: `❌ ${res.data?.message || 'Failed to deploy strategy.'}` });
        setTimeout(() => setDeploySuccessMsg(null), 10000);
      }
    } catch (err) {
       setDeploySuccessMsg({ type: 'error', text: `❌ Error: ${err.message}` });
       setTimeout(() => setDeploySuccessMsg(null), 5000);
    }
  };

  return (
    <div className="flex flex-col gap-6 min-h-[calc(100vh-80px)] text-slate-800 bg-[#fbfcfd] p-4 rounded-[32px] font-sans shadow-[inset_0_4px_20px_rgba(0,0,0,0.02)] relative overflow-hidden border border-slate-100/50 w-full max-w-full">
      
      {/* Soft Animated Background Orbs */}
      <div className="absolute top-[-10%] left-[-5%] w-[500px] h-[500px] bg-indigo-100/40 blur-[100px] rounded-full pointer-events-none z-0 mix-blend-multiply"></div>
      <div className="absolute bottom-[-10%] right-[-5%] w-[600px] h-[600px] bg-rose-50/50 blur-[120px] rounded-full pointer-events-none z-0 mix-blend-multiply"></div>

      {/* Floating Header */}
      <div className="relative z-10 flex flex-wrap items-center justify-between bg-white/70 backdrop-blur-2xl px-8 py-5 rounded-3xl border border-white/60 shadow-[0_8px_30px_rgb(0,0,0,0.04)]">
        <div className="flex items-center gap-5">
          <div className="w-12 h-12 rounded-2xl bg-gradient-to-tr from-indigo-500 via-purple-500 to-indigo-400 p-[1px] shadow-[0_8px_20px_rgba(99,102,241,0.25)]">
            <div className="w-full h-full bg-white/90 backdrop-blur-sm rounded-[15px] flex items-center justify-center">
              <svg className="w-6 h-6 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z"></path></svg>
            </div>
          </div>
          <div>
            <h1 className="text-2xl md:text-3xl font-black bg-gradient-to-br from-slate-800 to-slate-500 bg-clip-text text-transparent tracking-tight">Strategy Architect</h1>
            <p className="text-[11px] text-slate-400 uppercase tracking-[0.25em] mt-1 font-black">Visual Options Engine</p>
          </div>
        </div>
        
        <div className="flex items-center gap-4 mt-4 md:mt-0">

          <div className="relative group">
            <select
              value={underlying}
              onChange={e => { setUnderlying(e.target.value); setExpiry(''); setLegs([]); }}
              className="appearance-none bg-white hover:bg-slate-50 border border-slate-200 text-slate-700 font-black text-sm px-6 py-3 pr-12 rounded-2xl outline-none cursor-pointer transition-all shadow-[0_4px_15px_rgba(0,0,0,0.03)] hover:shadow-[0_8px_25px_rgba(0,0,0,0.06)]"
            >
              <option value="NIFTY">NIFTY 50</option>
              <option value="BANKNIFTY">BANK NIFTY</option>
              <option value="FINNIFTY">FIN NIFTY</option>
              <option value="MIDCPNIFTY">MIDCAP NIFTY</option>
              <option value="SENSEX">SENSEX</option>
              <option value="BANKEX">BANKEX</option>
            </select>
            <svg className="w-5 h-5 absolute right-4 top-1/2 -translate-y-1/2 pointer-events-none text-indigo-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="3" d="M19 9l-7 7-7-7"></path></svg>
          </div>

          {chainData && (
            <div className="flex items-center gap-3">
              <div className="bg-indigo-50 border border-indigo-100/50 px-6 py-3 rounded-2xl shadow-[0_4px_15px_rgba(99,102,241,0.1)] flex items-center gap-3">
                <div className="flex items-center gap-2">
                   <div className="w-2.5 h-2.5 bg-indigo-500 rounded-full animate-pulse shadow-[0_0_12px_rgba(99,102,241,0.8)]"></div>
                   <span className="text-[11px] text-indigo-400 uppercase tracking-widest font-black">Spot</span>
                </div>
                <span className="text-[15px] md:text-lg font-black text-indigo-700">{chainData.spotPrice.toLocaleString()}</span>
              </div>
              <div className="bg-slate-50 border border-slate-200 px-4 py-3 rounded-2xl flex items-center gap-2">
                <span className="text-[10px] text-slate-400 uppercase tracking-widest font-black">Lot</span>
                <span className="text-sm font-black text-slate-700">{chainData.lotSize || '—'}</span>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* TWO COLUMNS: Option Chain (Left), Payoff & Basket (Right) */}
      <div className="flex flex-col lg:flex-row gap-6 flex-1 min-h-0 relative z-10 items-stretch">
        
        {/* LEFT: Option Chain */}
        <div className="w-full lg:w-[55%] flex flex-col bg-white/60 backdrop-blur-xl rounded-3xl border border-white shadow-[0_12px_40px_rgba(0,0,0,0.03)] overflow-hidden h-[600px] lg:h-auto">
          <div className="px-5 pt-4 pb-2 border-b border-slate-100/60 bg-white/40">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs md:text-sm font-black text-slate-800 uppercase tracking-[0.2em]">Real-Time Chain</h3>
              <button onClick={scrollToATM} className="text-[10px] font-black tracking-widest text-indigo-500 hover:text-white border border-indigo-200 hover:bg-indigo-500 px-3 py-1.5 rounded-lg transition-colors flex items-center gap-1 shadow-sm uppercase">
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="3" d="M19 14l-7 7m0 0l-7-7m7 7V3"></path></svg>
                  Go To ATM
              </button>
            </div>
            <div className="flex items-center gap-1.5 overflow-x-auto pb-2 -mx-1 px-1 scrollbar-hide">
              <span className="text-[9px] text-slate-400 font-black uppercase tracking-widest mr-1 shrink-0">Expiry</span>
              {generatedExpiries.map((exp, idx) => {
                const isActive = expiry ? expiry === exp : chainData?.expiry === exp;
                const d = new Date(exp + 'T00:00:00');
                const label = d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
                const isWeekly = idx < 6 && underlying === 'NIFTY';
                const isMonthly = !isWeekly || (d.getMonth() !== new Date(generatedExpiries[Math.min(idx+1, generatedExpiries.length-1)] + 'T00:00:00').getMonth());
                return (
                  <button
                    key={exp}
                    onClick={() => { setExpiry(exp); setLegs([]); }}
                    className={`shrink-0 px-3 py-1.5 rounded-lg text-[11px] font-bold transition-all cursor-pointer ${
                      isActive
                        ? 'bg-gradient-to-r from-indigo-500 to-purple-500 text-white shadow-[0_4px_12px_rgba(99,102,241,0.35)] scale-105'
                        : 'bg-slate-50 hover:bg-indigo-50 text-slate-600 hover:text-indigo-600 border border-slate-200 hover:border-indigo-300'
                    }`}
                  >
                    {label}
                    {isMonthly && <span className={`ml-1 text-[8px] font-black uppercase ${isActive ? 'text-indigo-200' : 'text-amber-500'}`}>M</span>}
                  </button>
                );
              })}
              {generatedExpiries.length === 0 && (
                <span className="text-[10px] text-slate-400 italic">Loading expiries...</span>
              )}
            </div>
          </div>
          
          <div className="flex-1 overflow-auto light-scrollbar relative" ref={chainContainerRef}>
            <table className="w-full text-left border-collapse">
              <thead className="bg-white/95 backdrop-blur-md sticky top-0 z-20">
                <tr>
                  <th colSpan="5" className="py-3 text-center text-[10px] font-black text-emerald-600 tracking-[0.25em] border-b border-slate-100 bg-emerald-50/30">CALLS</th>
                  <th className="py-3 text-center text-[10px] font-black text-slate-400 tracking-[0.25em] border-b border-slate-100 bg-white">STRIKE</th>
                  <th colSpan="5" className="py-3 text-center text-[10px] font-black text-rose-600 tracking-[0.25em] border-b border-slate-100 bg-rose-50/30">PUTS</th>
                </tr>
                <tr>
                  <th className="p-2.5 text-center text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 w-12 bg-white/50">OI</th>
                  <th className="p-1.5 text-right text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 bg-white/50">BID</th>
                  <th className="p-2.5 text-right text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 bg-white/50">LTP</th>
                  <th className="p-1.5 text-right text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 bg-white/50">ASK</th>
                  <th className="p-2.5 text-center text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 w-20 bg-white/50">ACTION</th>

                  <th className="p-2.5 border-b border-slate-100 bg-slate-50/30"></th>

                  <th className="p-2.5 text-center text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 w-20 bg-white/50">ACTION</th>
                  <th className="p-1.5 text-left text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 bg-white/50">BID</th>
                  <th className="p-2.5 text-left text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 bg-white/50">LTP</th>
                  <th className="p-1.5 text-left text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 bg-white/50">ASK</th>
                  <th className="p-2.5 text-center text-[9px] font-black tracking-widest text-slate-400 border-b border-slate-100 w-12 bg-white/50">OI</th>
                </tr>
              </thead>
              <tbody>
                {chainLoading ? (
                  <tr><td colSpan="11" className="text-center p-20 text-slate-400 text-xs font-black tracking-widest animate-pulse uppercase">Connecting to Feed...</td></tr>
                ) : (
                  chainData?.chain?.map(row => {
                    const spot = chainData.spotPrice;
                    const isAtm = Math.abs(row.strike - spot) < 25;
                    const callItm = row.strike < spot;
                    const putItm = row.strike > spot;

                    return (
                      <tr key={row.strike} className={`group hover:bg-white transition-all duration-300 ${isAtm ? 'atm-row' : ''}`}>
                        {/* CALLS */}
                        <td className={`p-2.5 text-center text-[11px] font-bold text-slate-400 border-b border-slate-100/40 ${callItm ? 'bg-amber-50/30' : ''}`}>{row.ceOi ? row.ceOi.toLocaleString() : '-'}</td>
                        <td className={`p-1.5 text-right text-[11px] font-bold text-emerald-600 border-b border-slate-100/40 ${callItm ? 'bg-amber-50/30' : ''}`}>{row.ceBid > 0 ? row.ceBid.toFixed(2) : '-'}</td>
                        <td className={`p-2.5 text-right border-b border-slate-100/40 ${callItm ? 'bg-amber-50/30' : ''}`}>
                          <span className="text-[13px] font-black text-slate-700">{row.ceLtp?.toFixed(2) || '-'}</span>
                        </td>
                        <td className={`p-1.5 text-right text-[11px] font-bold text-rose-500 border-b border-slate-100/40 ${callItm ? 'bg-amber-50/30' : ''}`}>{row.ceAsk > 0 ? row.ceAsk.toFixed(2) : '-'}</td>
                        <td className={`p-1.5 text-center border-b border-slate-100/40 overflow-hidden ${callItm ? 'bg-amber-50/30' : ''}`}>
                           <div className="flex justify-center gap-1 opacity-0 group-hover:opacity-100 translate-x-4 group-hover:translate-x-0 transition-all duration-300">
                              <button onClick={() => addLeg(row.strike, 'CE', 'BUY', row.ceAsk || row.ceLtp)} title="Buy at Ask" className="w-8 h-6 rounded-md bg-emerald-50 text-emerald-600 border border-emerald-200 text-[10px] font-black hover:bg-emerald-500 hover:text-white hover:border-emerald-500 transition-all shadow-sm">B</button>
                              <button onClick={() => addLeg(row.strike, 'CE', 'SELL', row.ceBid || row.ceLtp)} title="Sell at Bid" className="w-8 h-6 rounded-md bg-rose-50 text-rose-600 border border-rose-200 text-[10px] font-black hover:bg-rose-500 hover:text-white hover:border-rose-500 transition-all shadow-sm">S</button>
                           </div>
                        </td>

                        {/* STRIKE */}
                        <td className={`p-2.5 text-center relative border-b border-slate-100/40 ${isAtm ? 'bg-indigo-50/30' : 'bg-slate-50/20'}`}>
                          {isAtm && (
                            <div className="absolute inset-0 border-y-2 border-indigo-400 bg-indigo-500/5 pointer-events-none z-0 shadow-[inset_0_0_12px_rgba(99,102,241,0.1)]"></div>
                          )}
                          <span className={`relative z-10 text-[13px] font-black ${isAtm ? 'text-indigo-700' : 'text-slate-800'}`}>
                            {row.strike}
                          </span>
                        </td>

                        {/* PUTS */}
                        <td className={`p-1.5 text-center border-b border-slate-100/40 overflow-hidden ${putItm ? 'bg-amber-50/30' : ''}`}>
                           <div className="flex justify-center gap-1 opacity-0 group-hover:opacity-100 -translate-x-4 group-hover:translate-x-0 transition-all duration-300">
                              <button onClick={() => addLeg(row.strike, 'PE', 'BUY', row.peAsk || row.peLtp)} title="Buy at Ask" className="w-8 h-6 rounded-md bg-emerald-50 text-emerald-600 border border-emerald-200 text-[10px] font-black hover:bg-emerald-500 hover:text-white hover:border-emerald-500 transition-all shadow-sm">B</button>
                              <button onClick={() => addLeg(row.strike, 'PE', 'SELL', row.peBid || row.peLtp)} title="Sell at Bid" className="w-8 h-6 rounded-md bg-rose-50 text-rose-600 border border-rose-200 text-[10px] font-black hover:bg-rose-500 hover:text-white hover:border-rose-500 transition-all shadow-sm">S</button>
                           </div>
                        </td>
                        <td className={`p-1.5 text-left text-[11px] font-bold text-emerald-600 border-b border-slate-100/40 ${putItm ? 'bg-amber-50/30' : ''}`}>{row.peBid > 0 ? row.peBid.toFixed(2) : '-'}</td>
                        <td className={`p-2.5 text-left border-b border-slate-100/40 ${putItm ? 'bg-amber-50/30' : ''}`}>
                          <span className="text-[13px] font-black text-slate-700">{row.peLtp?.toFixed(2) || '-'}</span>
                        </td>
                        <td className={`p-1.5 text-left text-[11px] font-bold text-rose-500 border-b border-slate-100/40 ${putItm ? 'bg-amber-50/30' : ''}`}>{row.peAsk > 0 ? row.peAsk.toFixed(2) : '-'}</td>
                        <td className={`p-2.5 text-center text-[11px] font-bold text-slate-400 border-b border-slate-100/40 ${putItm ? 'bg-amber-50/30' : ''}`}>{row.peOi ? row.peOi.toLocaleString() : '-'}</td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* RIGHT: Payoff Chart & Basket */}
        <div className="w-full lg:w-[45%] flex flex-col gap-6 lg:h-auto overflow-y-auto pr-2 pb-2 light-scrollbar">
          
          {/* TOP: Payoff Chart (Expanded Height) */}
          <div className="bg-white/60 backdrop-blur-xl rounded-3xl border border-white shadow-[0_12px_40px_rgba(0,0,0,0.03)] flex flex-col min-h-[380px] overflow-hidden shrink-0">
            <div className="px-6 py-4 border-b border-slate-100/60 flex items-center justify-between bg-white/40">
              <h3 className="text-xs font-black text-slate-800 uppercase tracking-[0.2em]">Payoff Topography</h3>
              <div className="w-2.5 h-2.5 rounded-full bg-indigo-500 shadow-[0_0_10px_rgba(99,102,241,0.6)] animate-pulse"></div>
            </div>
            <div className="p-6 flex-1 relative bg-white/30">
              {payoffData.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={payoffData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                    <defs>
                      <linearGradient id="splitColor" x1="0" y1="0" x2="0" y2="1">
                        <stop offset={off} stopColor="#34d399" stopOpacity={0.6}>
                          <animate attributeName="stopOpacity" values="0.6;0.2;0.6" dur="3s" repeatCount="indefinite" />
                        </stop>
                        <stop offset={off} stopColor="#f43f5e" stopOpacity={0.6}>
                          <animate attributeName="stopOpacity" values="0.6;0.2;0.6" dur="2s" repeatCount="indefinite" />
                        </stop>
                      </linearGradient>
                      <linearGradient id="splitStroke" x1="0" y1="0" x2="0" y2="1">
                        <stop offset={off} stopColor="#10b981" stopOpacity={1}/>
                        <stop offset={off} stopColor="#f43f5e" stopOpacity={1}/>
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                    <XAxis 
                      dataKey="spot" 
                      type="number" 
                      domain={['dataMin', 'dataMax']} 
                      tickFormatter={(val) => val.toLocaleString()}
                      tick={{fontSize: 10, fill: '#94a3b8', fontWeight: 800}}
                      tickCount={7}
                      axisLine={false}
                      tickLine={false}
                      dy={10}
                    />
                    <YAxis 
                      tickFormatter={(val) => {
                         if (Math.abs(val) >= 1000) return (val/1000).toFixed(1) + 'k';
                         return val;
                      }}
                      tick={{fontSize: 10, fill: '#94a3b8', fontWeight: 800}}
                      axisLine={false}
                      tickLine={false}
                      width={50}
                    />
                    <Tooltip 
                      formatter={(value) => ['' + value.toLocaleString(), 'P&L']}
                      labelFormatter={(label) => 'SPOT: ' + label.toLocaleString()}
                      contentStyle={{ borderRadius: '16px', border: '1px solid #cbd5e1', backgroundColor: 'rgba(255,255,255,0.9)', backdropFilter: 'blur(10px)', fontSize: '12px', fontWeight: 800, padding: '12px 18px', boxShadow: '0 10px 30px -5px rgba(0,0,0,0.1)' }}
                      itemStyle={{ color: '#0f172a', fontWeight: 900, marginTop: '2px' }}
                      labelStyle={{ color: '#64748b', fontSize: '9px', textTransform: 'uppercase', letterSpacing: '0.1em' }}
                      cursor={{ stroke: '#6366f1', strokeWidth: 2, strokeDasharray: '4 4' }}
                    />
                    <ReferenceLine y={0} stroke="#94a3b8" strokeDasharray="3 3" />
                    {chainData?.spotPrice && (
                      <ReferenceLine x={chainData.spotPrice} stroke="#4f46e5" strokeWidth={2} strokeDasharray="4 4" label={{ position: 'top', value: 'SPOT', fill: '#4f46e5', fontSize: 10, fontWeight: 900, dy: -10, letterSpacing: '0.1em' }} />
                    )}
                    
                    <Area 
                       type="monotone" 
                       dataKey="pnl" 
                       stroke="url(#splitStroke)" 
                       fillOpacity={1} 
                       fill="url(#splitColor)" 
                       strokeWidth={3} 
                    />
                  </AreaChart>
                </ResponsiveContainer>
              ) : (
                <div className="absolute inset-0 flex items-center justify-center">
                   <div className="flex flex-col items-center gap-3 opacity-50">
                     <svg className="w-8 h-8 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path></svg>
                     <span className="text-slate-500 text-[10px] font-black tracking-[0.2em] uppercase">Add legs to visualize payoff</span>
                   </div>
                </div>
              )}
            </div>
          </div>

          {/* BOTTOM: Strategy Basket & Metrics */}
          <div className="bg-white/60 backdrop-blur-xl rounded-3xl border border-white shadow-[0_12px_40px_rgba(0,0,0,0.03)] flex flex-col shrink-0 flex-1 relative z-10">
            <div className="px-6 py-4 border-b border-slate-100/60 flex flex-wrap gap-4 items-center justify-between bg-white/40">
              <h3 className="text-xs font-black text-slate-800 uppercase tracking-[0.2em]">Strategy Basket</h3>
              
              <div className="flex items-center gap-3">
                {legs.length > 0 && (
                  <button onClick={reverseStrategy} className="group flex items-center gap-1.5 text-[10px] font-black text-indigo-500 hover:text-indigo-600 bg-indigo-50 hover:bg-indigo-100 border border-indigo-100 px-2 py-1 rounded-lg uppercase tracking-widest transition-all">
                    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"></path></svg>
                    Reverse
                  </button>
                )}
                {legs.length > 0 && (
                  <button onClick={clearAll} className="group flex items-center gap-1.5 text-[10px] font-black text-slate-400 hover:text-rose-500 uppercase tracking-widest transition-all">
                    <div className="w-5 h-5 rounded-md bg-slate-100 group-hover:bg-rose-100 flex items-center justify-center transition-colors">
                      <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path></svg>
                    </div>
                  </button>
                )}
                <div className="flex items-center bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
                  <span className="px-3 text-[10px] font-black text-slate-400 tracking-widest bg-slate-50 border-r border-slate-200 h-full flex items-center">LOTS</span>
                  <input type="number" min="1" value={lots} onChange={e => setLots(parseInt(e.target.value) || 1)} className="w-14 text-center text-sm font-black text-indigo-600 py-1.5 bg-transparent outline-none" />
                </div>
              </div>
            </div>
            
            <div className="p-5 flex flex-col gap-3 min-h-[160px] max-h-[350px] overflow-y-auto light-scrollbar bg-slate-50/30">
              {legs.length === 0 ? (
                <div className="flex flex-col gap-4 animate-in fade-in duration-500 h-full justify-center">
                    <div className="text-[10px] font-black text-slate-400 tracking-widest uppercase text-center mb-1">Prebuilt Strategies:</div>
                    <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
                        <button onClick={() => buildStrategy('STRADDLE')} className="py-3 px-2 bg-white border border-slate-200 rounded-xl hover:border-indigo-400 hover:shadow-md transition-all text-[11px] font-black text-slate-600 uppercase tracking-wider flex flex-col items-center gap-1.5">
                            <svg className="w-5 h-5 text-indigo-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 15l7-7 7 7"></path></svg>
                            Straddle
                        </button>
                        <button onClick={() => buildStrategy('STRANGLE')} className="py-3 px-2 bg-white border border-slate-200 rounded-xl hover:border-indigo-400 hover:shadow-md transition-all text-[11px] font-black text-slate-600 uppercase tracking-wider flex flex-col items-center gap-1.5">
                            <svg className="w-5 h-5 text-indigo-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 14h4l4-8h8"></path></svg>
                            Strangle
                        </button>
                        <button onClick={() => buildStrategy('BULL_CALL')} className="py-3 px-2 bg-white border border-slate-200 rounded-xl hover:border-emerald-400 hover:shadow-md transition-all text-[11px] font-black text-slate-600 uppercase tracking-wider flex flex-col items-center gap-1.5">
                            <svg className="w-5 h-5 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"></path></svg>
                            Bull Call
                        </button>
                        <button onClick={() => buildStrategy('BEAR_PUT')} className="py-3 px-2 bg-white border border-slate-200 rounded-xl hover:border-rose-400 hover:shadow-md transition-all text-[11px] font-black text-slate-600 uppercase tracking-wider flex flex-col items-center gap-1.5">
                            <svg className="w-5 h-5 text-rose-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 17h8m0 0V9m0 8l-8-8-4 4-6-6"></path></svg>
                            Bear Put
                        </button>
                        <button onClick={() => buildStrategy('IRON_CONDOR')} className="py-3 px-2 bg-white border border-slate-200 rounded-xl hover:border-purple-400 hover:shadow-md transition-all text-[11px] font-black text-slate-600 uppercase tracking-wider flex flex-col items-center gap-1.5">
                            <svg className="w-5 h-5 text-purple-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M3 14l4-4 4 4h6l4 4"></path></svg>
                            Iron Condor
                        </button>
                        <button onClick={() => buildStrategy('IRON_FLY')} className="py-3 px-2 bg-white border border-slate-200 rounded-xl hover:border-amber-400 hover:shadow-md transition-all text-[11px] font-black text-slate-600 uppercase tracking-wider flex flex-col items-center gap-1.5">
                            <svg className="w-5 h-5 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 14l8-8 8 8"></path></svg>
                            Iron Fly
                        </button>
                    </div>
                </div>
              ) : (
                legs.map(leg => (
                  <div key={leg.id} className="flex items-center justify-between bg-white border border-slate-200 shadow-sm rounded-2xl p-3 pl-4 group hover:border-indigo-300 hover:shadow-md transition-all duration-300">
                    <div className="flex items-center gap-4">
                      {/* Clickable Badge to Toggle Side */}
                      <button 
                        onClick={() => toggleLegSide(leg.id)}
                        title="Click to flip Buy/Sell"
                        className={`w-12 h-12 rounded-xl flex items-center justify-center text-sm font-black tracking-wider shadow-inner cursor-pointer hover:scale-105 transition-all ${leg.side === 'BUY' ? 'bg-gradient-to-br from-emerald-50 to-emerald-100 text-emerald-600 border border-emerald-200' : 'bg-gradient-to-br from-rose-50 to-rose-100 text-rose-600 border border-rose-200'}`}
                      >
                        {leg.side === 'BUY' ? 'B' : 'S'}
                      </button>
                      <div>
                        <div className="flex items-center gap-2.5 mb-1">
                          <span className="text-base font-black text-slate-800">{leg.strike}</span>
                          <span className={`text-[10px] font-black px-2 py-0.5 rounded uppercase ${leg.optionType === 'CE' ? 'bg-purple-100 text-purple-700' : 'bg-orange-100 text-orange-700'}`}>{leg.optionType}</span>
                          {leg.qty > 1 && <span className="text-[10px] font-black text-indigo-500 bg-indigo-50 px-2 rounded-full border border-indigo-100">x{leg.qty}</span>}
                        </div>
                        <div className="text-[11px] font-bold text-slate-400 tracking-wide">ENTRY @ <span className="text-slate-600 font-black">{leg.price.toFixed(2)}</span></div>
                      </div>
                    </div>
                    <button onClick={() => removeLeg(leg.id)} className="w-9 h-9 rounded-full flex items-center justify-center text-slate-300 hover:text-white hover:bg-rose-500 hover:shadow-lg hover:shadow-rose-500/30 transition-all mr-1">
                      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M6 18L18 6M6 6l12 12"></path></svg>
                    </button>
                  </div>
                ))
              )}
            </div>

            {legs.length > 0 && metrics && (
              <div className="p-6 bg-white/80 backdrop-blur-md border-t border-slate-100 rounded-b-3xl">
                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
                  <div className={`p-4 rounded-2xl text-center border transition-all duration-500 ${metrics.maxProfit > 0 || metrics.maxProfit === 'Unlimited' ? 'bg-emerald-50/80 border-emerald-200 shadow-[0_0_20px_rgba(16,185,129,0.15)]' : 'bg-slate-50 border-slate-200 shadow-sm'}`}>
                    <div className="text-[9px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Max Profit</div>
                    <div className={`text-[14px] lg:text-[15px] font-black tracking-wide ${metrics.maxProfit === 'Unlimited' || metrics.maxProfit > 0 ? 'text-emerald-600' : 'text-slate-400'}`}>
                      {metrics.maxProfit === 'Unlimited' ? 'UNLIMITED' : `${metrics.maxProfit.toLocaleString()}`}
                    </div>
                  </div>
                  
                  {/* RED SHADE ANIMATED LOSS CARD */}
                  <div className={`relative p-4 rounded-2xl text-center border transition-all duration-500 overflow-hidden ${metrics.maxLoss < 0 || metrics.maxLoss === 'Unlimited' ? 'bg-rose-50/90 border-rose-300 shadow-[0_0_25px_rgba(244,63,94,0.3)] animate-[pulse_3s_ease-in-out_infinite]' : 'bg-slate-50 border-slate-200 shadow-sm'}`}>
                    {metrics.maxLoss < 0 && <div className="absolute inset-0 bg-gradient-to-b from-rose-100/50 to-transparent pointer-events-none"></div>}
                    <div className="relative z-10 text-[9px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Max Loss</div>
                    <div className={`relative z-10 text-[14px] lg:text-[15px] font-black tracking-wide ${metrics.maxLoss === 'Unlimited' || metrics.maxLoss < 0 ? 'text-rose-600' : 'text-slate-400'}`}>
                      {metrics.maxLoss === 'Unlimited' ? 'UNLIMITED' : `${Math.abs(metrics.maxLoss).toLocaleString()}`}
                    </div>
                  </div>
                  
                  <div className="p-4 rounded-2xl text-center border border-slate-200 bg-white shadow-sm">
                    <div className="text-[9px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Net Premium</div>
                    <div className={`text-[14px] lg:text-[15px] font-black tracking-wide ${metrics.netPremium > 0 ? 'text-emerald-500' : 'text-rose-500'}`}>
                      {metrics.netPremium > 0 ? '+' : ''}{metrics.netPremium.toLocaleString(undefined, {maximumFractionDigits:0})}
                    </div>
                  </div>
                  <div className="p-4 rounded-2xl text-center border border-slate-200 bg-white shadow-sm">
                    <div className="text-[9px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Breakeven</div>
                    <div className="text-[13px] lg:text-[14px] font-black tracking-wide text-indigo-600">
                      {metrics.breakevenText}
                    </div>
                  </div>
                </div>

                <button onClick={deployStrategy} className="w-full relative overflow-hidden group bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white font-black py-4 rounded-2xl shadow-[0_10px_25px_-5px_rgba(99,102,241,0.5)] transition-all active:scale-[0.98] flex items-center justify-center gap-3">
                  <div className="absolute inset-0 w-full h-full bg-white/20 -translate-x-full group-hover:animate-[shimmer_1.5s_infinite] skew-x-12"></div>
                  <svg className="w-5 h-5 relative z-10" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
                  <span className="relative z-10 tracking-[0.2em] text-sm uppercase">Execute Strategy</span>
                </button>
                {deploySuccessMsg && (
                  <div className={`mt-3 p-3 rounded-xl border text-center text-[10px] font-black uppercase tracking-wider shadow-sm animate-[scale-in_0.2s_ease-out] ${deploySuccessMsg.type === 'success' ? 'bg-emerald-50 text-emerald-600 border-emerald-200' : 'bg-rose-50 text-rose-600 border-rose-200'}`}>
                    {deploySuccessMsg.text}
                  </div>
                )}
              </div>
            )}
          </div>

        </div>
      </div>
      
                  <style>{`
        .light-scrollbar::-webkit-scrollbar { width: 5px; height: 5px; }
        .light-scrollbar::-webkit-scrollbar-track { background: transparent; }
        .light-scrollbar::-webkit-scrollbar-thumb { background: #cbd5e1; border-radius: 10px; }
        .light-scrollbar::-webkit-scrollbar-thumb:hover { background: #94a3b8; }
      `}</style>
      
      {showDeployModal && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" onClick={() => setShowDeployModal(false)}></div>
          <div className="relative w-full max-w-md bg-white rounded-3xl shadow-[0_20px_60px_-15px_rgba(0,0,0,0.3)] border border-white/60 overflow-hidden animate-[scale-in_0.2s_ease-out]">
            <div className="absolute top-0 left-0 w-full h-1.5 bg-gradient-to-r from-indigo-500 to-purple-500"></div>
            
            <form onSubmit={confirmDeploy} className="p-8">
              <div className="flex items-center gap-4 mb-6">
                <div className="w-12 h-12 rounded-2xl bg-indigo-50 flex items-center justify-center border border-indigo-100">
                  <svg className="w-6 h-6 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
                </div>
                <div>
                  <h3 className="text-lg font-black text-slate-800">Deploy Strategy</h3>
                  <p className="text-[11px] font-bold text-slate-400">{executionBroker === 'PAPER' ? 'Save as PAPER Trade' : `Execute LIVE via ${executionBroker}`}</p>
                </div>
              </div>
              
              <div className="mb-8">
                <label className="block text-[10px] font-black text-slate-500 uppercase tracking-widest mb-2 ml-1">Strategy Name</label>
                <input 
                  type="text" 
                  autoFocus
                  required
                  value={deployName}
                  onChange={e => setDeployName(e.target.value)}
                  className="w-full bg-slate-50 border-2 border-slate-200 text-slate-800 font-bold text-sm rounded-xl px-4 py-3 outline-none focus:border-indigo-400 focus:bg-white transition-all shadow-inner"
                  placeholder="e.g. My NIFTY Condor..."
                />
              </div>
              
              <div className="flex items-center justify-end gap-3">
                <button type="button" onClick={() => setShowDeployModal(false)} className="px-5 py-2.5 rounded-xl font-bold text-slate-500 hover:bg-slate-100 hover:text-slate-700 transition-colors">
                  Cancel
                </button>
                <button type="submit" className="px-6 py-2.5 rounded-xl font-black text-white bg-indigo-600 hover:bg-indigo-700 shadow-md shadow-indigo-500/20 active:scale-95 transition-all">
                  Confirm & Deploy
                </button>
              </div>
            </form>
          </div>
        </div>
      )}


    </div>
  );
}

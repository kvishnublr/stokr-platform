import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

export default function Settings() {
  const queryClient = useQueryClient();
  const { data: profile, isLoading: profileLoading } = useQuery({ queryKey: ['profile'], queryFn: () => client.get('/profile').then((r) => r.data) });
  const { data: traderConfig, isLoading: configLoading } = useQuery({
    queryKey: ['trader-config'],
    queryFn: () => client.get('/chartink/trader-config/1').then((r) => r.data),
    retry: 1
  });

  const [profileForm, setProfileForm] = useState({ name: '', phone: '', totalCapital: '', riskProfile: 'MODERATE' });
  const [configForm, setConfigForm] = useState({
    mode: 'PAPER',
    capital: 15000,
    maxPositions: 3,
    minSharePrice: 200,
    maxSharePrice: 3000,
    stopLossPct: 0.2,
    targetPct: 0.6,
    maxDailyLoss: 225,
    minTradeGapMinutes: 2,
    maxConsecutiveLosses: 3,
    enabled: true
  });
  const [profileInit, setProfileInit] = useState(false);
  const [configInit, setConfigInit] = useState(false);

  useEffect(() => {
    if (profile && !profileInit) {
      setProfileForm({ name: profile.name || '', phone: profile.phone || '', totalCapital: profile.totalCapital || '', riskProfile: profile.riskProfile || 'MODERATE' });
      setProfileInit(true);
    }
  }, [profile, profileInit]);

  useEffect(() => {
    if (traderConfig && !configInit) {
      setConfigForm({
        mode: traderConfig.mode || 'PAPER',
        capital: traderConfig.capital || 15000,
        maxPositions: traderConfig.maxPositions || 3,
        minSharePrice: traderConfig.minSharePrice || 200,
        maxSharePrice: traderConfig.maxSharePrice || 3000,
        stopLossPct: traderConfig.stopLossPct || 0.2,
        targetPct: traderConfig.targetPct || 0.6,
        maxDailyLoss: traderConfig.maxDailyLoss || 225,
        minTradeGapMinutes: traderConfig.minTradeGapMinutes || 2,
        maxConsecutiveLosses: traderConfig.maxConsecutiveLosses || 3,
        enabled: traderConfig.enabled !== undefined ? traderConfig.enabled : true
      });
      setConfigInit(true);
    }
  }, [traderConfig, configInit]);

  const updateProfileMutation = useMutation({
    mutationFn: (data) => client.put('/profile', data),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['profile'] }); alert('Profile updated!'); },
  });

  const updateConfigMutation = useMutation({
    mutationFn: (data) => client.put('/chartink/trader-config/1', data),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['trader-config'] }); alert('Trading config saved!'); },
  });

  const toggleModeMutation = useMutation({
    mutationFn: (mode) => client.post(`/chartink/trader-config/1/mode?mode=${mode}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['trader-config'] }),
  });

  if (profileLoading || configLoading) return <div className="text-slate-500">Loading...</div>;

  const inputCls = 'w-full px-4 py-2.5 bg-white border border-slate-200 rounded-xl text-slate-800 focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition outline-none text-sm input-crystal';
  const cardCls = 'card-crystal p-6';

  return (
    <div>
      <div className="mb-8 animate-fade-in-up">
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Settings</h1>
        </div>
        <p className="text-slate-400 text-sm ml-4">Manage your profile and trading preferences</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Profile Card */}
        <div className={cardCls}>
          <h2 className="text-base font-semibold text-slate-800 mb-4">Profile</h2>
          <div className="space-y-4">
            <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Name</label><input value={profileForm.name} onChange={(e) => setProfileForm({ ...profileForm, name: e.target.value })} className={inputCls} /></div>
            <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Phone</label><input value={profileForm.phone} onChange={(e) => setProfileForm({ ...profileForm, phone: e.target.value })} className={inputCls} /></div>
            <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Total Capital (&#8377;)</label><input type="number" value={profileForm.totalCapital} onChange={(e) => setProfileForm({ ...profileForm, totalCapital: Number(e.target.value) })} className={inputCls} /></div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Risk Profile</label>
              <select value={profileForm.riskProfile} onChange={(e) => setProfileForm({ ...profileForm, riskProfile: e.target.value })} className={inputCls}>
                <option value="CONSERVATIVE">Conservative</option><option value="MODERATE">Moderate</option><option value="AGGRESSIVE">Aggressive</option>
              </select>
            </div>
            <button onClick={() => updateProfileMutation.mutate(profileForm)} disabled={updateProfileMutation.isPending}
              className="bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium hover:from-indigo-700 hover:to-violet-700 disabled:opacity-50 transition shadow-lg shadow-indigo-500/20">
              {updateProfileMutation.isPending ? 'Saving...' : 'Save Profile'}
            </button>
          </div>
        </div>

        {/* Trader Config Card */}
        <div className={cardCls}>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-base font-semibold text-slate-800">Auto-Trading Configuration</h2>
            <span className={`px-2.5 py-1 rounded-full text-xs font-bold ${configForm.mode === 'LIVE' ? 'bg-rose-50 text-rose-600 border border-rose-200' : 'bg-emerald-50 text-emerald-600 border border-emerald-200'}`}>
              {configForm.mode}
            </span>
          </div>

          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Mode</label>
                <select value={configForm.mode} onChange={(e) => setConfigForm({ ...configForm, mode: e.target.value })} className={inputCls}>
                  <option value="PAPER">Paper Trading</option>
                  <option value="LIVE">Live Trading</option>
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Capital (&#8377;)</label>
                <input type="number" value={configForm.capital} onChange={(e) => setConfigForm({ ...configForm, capital: Number(e.target.value) })} className={inputCls} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Max Positions</label>
                <input type="number" value={configForm.maxPositions} onChange={(e) => setConfigForm({ ...configForm, maxPositions: Number(e.target.value) })} className={inputCls} />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Max Daily Loss (&#8377;)</label>
                <input type="number" value={configForm.maxDailyLoss} onChange={(e) => setConfigForm({ ...configForm, maxDailyLoss: Number(e.target.value) })} className={inputCls} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Min Share Price (&#8377;)</label>
                <input type="number" value={configForm.minSharePrice} onChange={(e) => setConfigForm({ ...configForm, minSharePrice: Number(e.target.value) })} className={inputCls} />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Max Share Price (&#8377;)</label>
                <input type="number" value={configForm.maxSharePrice} onChange={(e) => setConfigForm({ ...configForm, maxSharePrice: Number(e.target.value) })} className={inputCls} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Stop Loss (%)</label>
                <input type="number" step="0.1" value={configForm.stopLossPct} onChange={(e) => setConfigForm({ ...configForm, stopLossPct: Number(e.target.value) })} className={inputCls} />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Target (%)</label>
                <input type="number" step="0.1" value={configForm.targetPct} onChange={(e) => setConfigForm({ ...configForm, targetPct: Number(e.target.value) })} className={inputCls} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Min Trade Gap (min)</label>
                <input type="number" value={configForm.minTradeGapMinutes} onChange={(e) => setConfigForm({ ...configForm, minTradeGapMinutes: Number(e.target.value) })} className={inputCls} />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">Max Consecutive Losses</label>
                <input type="number" value={configForm.maxConsecutiveLosses} onChange={(e) => setConfigForm({ ...configForm, maxConsecutiveLosses: Number(e.target.value) })} className={inputCls} />
              </div>
            </div>

            <div className="flex items-center gap-3 pt-2">
              <input type="checkbox" id="enabled" checked={configForm.enabled} onChange={(e) => setConfigForm({ ...configForm, enabled: e.target.checked })} className="w-4 h-4 text-indigo-600 rounded border-slate-300" />
              <label htmlFor="enabled" className="text-sm font-medium text-slate-700">Auto-trading enabled</label>
            </div>

            <div className="flex gap-3 pt-2">
              <button onClick={() => updateConfigMutation.mutate(configForm)} disabled={updateConfigMutation.isPending}
                className="bg-gradient-to-r from-emerald-500 to-teal-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium hover:from-emerald-600 hover:to-teal-700 disabled:opacity-50 transition shadow-lg shadow-emerald-500/20">
                {updateConfigMutation.isPending ? 'Saving...' : 'Save Trading Config'}
              </button>
              <button onClick={() => toggleModeMutation.mutate(configForm.mode === 'LIVE' ? 'PAPER' : 'LIVE')} disabled={toggleModeMutation.isPending}
                className={`px-5 py-2.5 rounded-xl text-sm font-medium transition shadow-lg ${configForm.mode === 'LIVE' ? 'bg-amber-500 text-white hover:bg-amber-600 shadow-amber-500/20' : 'bg-rose-500 text-white hover:bg-rose-600 shadow-rose-500/20'}`}>
                {toggleModeMutation.isPending ? 'Switching...' : (configForm.mode === 'LIVE' ? 'Switch to Paper' : 'Switch to Live')}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

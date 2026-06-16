import { useState, useEffect } from 'react'
import { useAuthStore } from '../state/authStore'
import api from '../services/api'
import { 
  TrendingUp, Users, BarChart3, Activity, 
  Plus, Play, Pause, Square, ArrowUpRight, ArrowDownRight,
  Menu, LogOut
} from 'lucide-react'

interface Instance {
  id: string
  name: string
  symbol: string
  status: string
  executionMode: string
  allocation: number
  totalPnl: number
  pendingSignals: number
  openPositions: number
}

interface PortfolioSummary {
  totalPositions: number
  openPositions: number
  unrealizedPnl: number
  realizedPnl: number
  totalInvested: number
}

export default function DashboardPage() {
  const { user, logout } = useAuthStore()
  const [instances, setInstances] = useState<Instance[]>([])
  const [portfolio, setPortfolio] = useState<PortfolioSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [sidebarOpen, setSidebarOpen] = useState(false)

  useEffect(() => {
    fetchData()
  }, [])

  const fetchData = async () => {
    try {
      const [instancesRes, portfolioRes] = await Promise.all([
        api.get('/api/v1/instances'),
        api.get('/api/v1/portfolio/summary').catch(() => ({ data: null }))
      ])
      setInstances(instancesRes.data)
      setPortfolio(portfolioRes.data)
    } catch (error) {
      console.error('Failed to fetch data:', error)
    } finally {
      setLoading(false)
    }
  }

  const handleInstanceAction = async (id: string, action: 'start' | 'stop' | 'pause') => {
    try {
      await api.post(`/api/v1/instances/${id}/${action}`)
      fetchData()
    } catch (error) {
      console.error(`Failed to ${action} instance:`, error)
    }
  }

  const totalPnl = instances.reduce((sum, i) => sum + (i.totalPnl || 0), 0)

  return (
    <div className="min-h-screen bg-gray-900">
      {/* Header */}
      <header className="bg-gray-800 border-b border-gray-700">
        <div className="max-w-7xl mx-auto px-4 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <button onClick={() => setSidebarOpen(!sidebarOpen)} className="lg:hidden">
                <Menu className="w-6 h-6 text-gray-400" />
              </button>
              <div className="flex items-center space-x-2">
                <TrendingUp className="w-8 h-8 text-blue-500" />
                <span className="text-xl font-bold text-white">Stokr LITE</span>
              </div>
            </div>
            <div className="flex items-center space-x-4">
              <span className="text-gray-400">Welcome, {user?.firstName || user?.email}</span>
              <button onClick={logout} className="text-gray-400 hover:text-white">
                <LogOut className="w-5 h-5" />
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Navigation */}
      <nav className="bg-gray-800 border-b border-gray-700">
        <div className="max-w-7xl mx-auto px-4">
          <div className="flex space-x-8 py-3">
            <a href="/" className="text-blue-400 font-medium">Dashboard</a>
            <a href="/strategies" className="text-gray-400 hover:text-white">Strategies</a>
            <a href="/instances" className="text-gray-400 hover:text-white">Instances</a>
            <a href="/orders" className="text-gray-400 hover:text-white">Orders</a>
            <a href="/positions" className="text-gray-400 hover:text-white">Positions</a>
          </div>
        </div>
      </nav>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 py-8">
        {loading ? (
          <div className="flex justify-center py-12">
            <Activity className="w-8 h-8 text-blue-500 animate-spin" />
          </div>
        ) : (
          <>
            {/* Stats Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
              <StatCard 
                title="Total P&L" 
                value={`₹${totalPnl.toFixed(2)}`}
                icon={<TrendingUp className="w-6 h-6" />}
                color={totalPnl >= 0 ? 'green' : 'red'}
              />
              <StatCard 
                title="Active Instances" 
                value={instances.filter(i => i.status === 'RUNNING').length.toString()}
                icon={<Activity className="w-6 h-6" />}
                color="blue"
              />
              <StatCard 
                title="Open Positions" 
                value={portfolio?.openPositions?.toString() || '0'}
                icon={<BarChart3 className="w-6 h-6" />}
                color="purple"
              />
              <StatCard 
                title="Total Invested" 
                value={`₹${(portfolio?.totalInvested || 0).toFixed(2)}`}
                icon={<Users className="w-6 h-6" />}
                color="gray"
              />
            </div>

            {/* Strategy Instances */}
            <div className="bg-gray-800 rounded-xl border border-gray-700">
              <div className="p-4 border-b border-gray-700 flex items-center justify-between">
                <h2 className="text-lg font-semibold text-white">Strategy Instances</h2>
                <button className="flex items-center space-x-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg text-white text-sm">
                  <Plus className="w-4 h-4" />
                  <span>New Instance</span>
                </button>
              </div>

              {instances.length === 0 ? (
                <div className="p-8 text-center text-gray-500">
                  <Activity className="w-12 h-12 mx-auto mb-4 opacity-50" />
                  <p>No strategy instances yet</p>
                  <p className="text-sm mt-2">Create a strategy and start your first instance</p>
                </div>
              ) : (
                <div className="divide-y divide-gray-700">
                  {instances.map((instance) => (
                    <div key={instance.id} className="p-4 hover:bg-gray-750">
                      <div className="flex items-center justify-between">
                        <div className="flex-1">
                          <div className="flex items-center space-x-3">
                            <h3 className="font-medium text-white">{instance.name}</h3>
                            <StatusBadge status={instance.status} />
                          </div>
                          <div className="flex items-center space-x-4 mt-1 text-sm text-gray-400">
                            <span>{instance.symbol}</span>
                            <span>{instance.executionMode}</span>
                            <span>₹{instance.allocation?.toFixed(0)} allocated</span>
                          </div>
                        </div>

                        <div className="flex items-center space-x-6">
                          <div className="text-right">
                            <div className={`text-lg font-semibold flex items-center justify-end ${
                              (instance.totalPnl || 0) >= 0 ? 'text-green-400' : 'text-red-400'
                            }`}>
                              {(instance.totalPnl || 0) >= 0 ? (
                                <ArrowUpRight className="w-4 h-4 mr-1" />
                              ) : (
                                <ArrowDownRight className="w-4 h-4 mr-1" />
                              )}
                              ₹{Math.abs(instance.totalPnl || 0).toFixed(2)}
                            </div>
                            <div className="text-xs text-gray-500">P&L</div>
                          </div>

                          <div className="flex items-center space-x-2">
                            {instance.status === 'STOPPED' && (
                              <button
                                onClick={() => handleInstanceAction(instance.id, 'start')}
                                className="p-2 bg-green-600 hover:bg-green-700 rounded-lg text-white"
                                title="Start"
                              >
                                <Play className="w-4 h-4" />
                              </button>
                            )}
                            {instance.status === 'RUNNING' && (
                              <>
                                <button
                                  onClick={() => handleInstanceAction(instance.id, 'pause')}
                                  className="p-2 bg-yellow-600 hover:bg-yellow-700 rounded-lg text-white"
                                  title="Pause"
                                >
                                  <Pause className="w-4 h-4" />
                                </button>
                                <button
                                  onClick={() => handleInstanceAction(instance.id, 'stop')}
                                  className="p-2 bg-red-600 hover:bg-red-700 rounded-lg text-white"
                                  title="Stop"
                                >
                                  <Square className="w-4 h-4" />
                                </button>
                              </>
                            )}
                            {instance.status === 'PAUSED' && (
                              <button
                                onClick={() => handleInstanceAction(instance.id, 'start')}
                                className="p-2 bg-green-600 hover:bg-green-700 rounded-lg text-white"
                                title="Resume"
                              >
                                <Play className="w-4 h-4" />
                              </button>
                            )}
                          </div>
                        </div>
                      </div>

                      <div className="flex items-center space-x-4 mt-2 text-xs text-gray-500">
                        <span>{instance.pendingSignals} pending signals</span>
                        <span>{instance.openPositions} open positions</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </>
        )}
      </main>
    </div>
  )
}

function StatCard({ title, value, icon, color }: { 
  title: string
  value: string
  icon: React.ReactNode
  color: 'green' | 'red' | 'blue' | 'purple' | 'gray'
}) {
  const colorClasses = {
    green: 'bg-green-900/30 text-green-400 border-green-800',
    red: 'bg-red-900/30 text-red-400 border-red-800',
    blue: 'bg-blue-900/30 text-blue-400 border-blue-800',
    purple: 'bg-purple-900/30 text-purple-400 border-purple-800',
    gray: 'bg-gray-700/30 text-gray-400 border-gray-700',
  }

  return (
    <div className={`rounded-xl border p-4 ${colorClasses[color]}`}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-sm opacity-75">{title}</span>
        {icon}
      </div>
      <div className="text-2xl font-bold">{value}</div>
    </div>
  )
}

function StatusBadge({ status }: { status: string }) {
  const statusClasses: Record<string, string> = {
    RUNNING: 'bg-green-900/50 text-green-400',
    STOPPED: 'bg-gray-700/50 text-gray-400',
    PAUSED: 'bg-yellow-900/50 text-yellow-400',
  }

  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${statusClasses[status] || statusClasses.STOPPED}`}>
      {status}
    </span>
  )
}

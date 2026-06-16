import { useState, useEffect } from 'react'
import api from '../services/api'
import { Plus, Play, Pause, Square, Edit2 } from 'lucide-react'

interface Instance {
  id: string
  name: string
  symbol: string
  status: string
  executionMode: string
  allocation: number
  totalPnl: number
}

export default function InstancesPage() {
  const [instances, setInstances] = useState<Instance[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchInstances()
  }, [])

  const fetchInstances = async () => {
    try {
      const response = await api.get('/api/v1/instances')
      setInstances(response.data)
    } catch (error) {
      console.error('Failed to fetch instances:', error)
    } finally {
      setLoading(false)
    }
  }

  const handleAction = async (id: string, action: string) => {
    try {
      await api.post(`/api/v1/instances/${id}/${action}`)
      fetchInstances()
    } catch (error) {
      console.error(`Failed to ${action}:`, error)
    }
  }

  return (
    <div className="min-h-screen bg-gray-900">
      <header className="bg-gray-800 border-b border-gray-700 p-4">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <h1 className="text-xl font-bold text-white">Strategy Instances</h1>
          <button className="flex items-center space-x-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg text-white">
            <Plus className="w-4 h-4" />
            <span>New Instance</span>
          </button>
        </div>
      </header>

      <main className="max-w-7xl mx-auto p-4">
        {loading ? (
          <div className="text-center py-12 text-gray-500">Loading...</div>
        ) : instances.length === 0 ? (
          <div className="text-center py-12">
            <p className="text-gray-400">No instances yet</p>
          </div>
        ) : (
          <div className="bg-gray-800 rounded-xl border border-gray-700 overflow-hidden">
            <table className="w-full">
              <thead className="bg-gray-700">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Name</th>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Symbol</th>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Status</th>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Mode</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Allocation</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">P&L</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-700">
                {instances.map((instance) => (
                  <tr key={instance.id} className="hover:bg-gray-750">
                    <td className="px-4 py-3 text-white">{instance.name}</td>
                    <td className="px-4 py-3 text-gray-400">{instance.symbol}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded text-xs ${
                        instance.status === 'RUNNING' ? 'bg-green-900/50 text-green-400' :
                        instance.status === 'PAUSED' ? 'bg-yellow-900/50 text-yellow-400' :
                        'bg-gray-700 text-gray-400'
                      }`}>
                        {instance.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-400">{instance.executionMode}</td>
                    <td className="px-4 py-3 text-right text-gray-300">₹{instance.allocation?.toFixed(0)}</td>
                    <td className={`px-4 py-3 text-right font-medium ${
                      instance.totalPnl >= 0 ? 'text-green-400' : 'text-red-400'
                    }`}>
                      ₹{Math.abs(instance.totalPnl || 0).toFixed(2)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end space-x-2">
                        {instance.status === 'STOPPED' && (
                          <button onClick={() => handleAction(instance.id, 'start')} className="p-1 text-green-400 hover:text-green-300">
                            <Play className="w-4 h-4" />
                          </button>
                        )}
                        {instance.status === 'RUNNING' && (
                          <>
                            <button onClick={() => handleAction(instance.id, 'pause')} className="p-1 text-yellow-400 hover:text-yellow-300">
                              <Pause className="w-4 h-4" />
                            </button>
                            <button onClick={() => handleAction(instance.id, 'stop')} className="p-1 text-red-400 hover:text-red-300">
                              <Square className="w-4 h-4" />
                            </button>
                          </>
                        )}
                        {instance.status === 'PAUSED' && (
                          <button onClick={() => handleAction(instance.id, 'start')} className="p-1 text-green-400 hover:text-green-300">
                            <Play className="w-4 h-4" />
                          </button>
                        )}
                        <button className="p-1 text-gray-400 hover:text-white">
                          <Edit2 className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </main>
    </div>
  )
}

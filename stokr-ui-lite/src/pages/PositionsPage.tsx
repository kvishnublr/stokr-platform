import { useState, useEffect } from 'react'
import api from '../services/api'

interface Position {
  id: string
  symbol: string
  side: string
  quantity: number
  avgPrice: number
  currentPrice: number
  unrealizedPnl: number
  positionValue: number
  status: string
}

export default function PositionsPage() {
  const [positions, setPositions] = useState<Position[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchPositions()
  }, [])

  const fetchPositions = async () => {
    try {
      const response = await api.get('/api/v1/positions')
      setPositions(response.data)
    } catch (error) {
      console.error('Failed to fetch positions:', error)
    } finally {
      setLoading(false)
    }
  }

  const totalPnl = positions.reduce((sum, p) => sum + (p.unrealizedPnl || 0), 0)
  const totalValue = positions.reduce((sum, p) => sum + (p.positionValue || 0), 0)

  return (
    <div className="min-h-screen bg-gray-900">
      <header className="bg-gray-800 border-b border-gray-700 p-4">
        <div className="max-w-7xl mx-auto">
          <h1 className="text-xl font-bold text-white">Positions</h1>
        </div>
      </header>

      <main className="max-w-7xl mx-auto p-4">
        {/* Summary Cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
          <div className="bg-gray-800 rounded-xl border border-gray-700 p-4">
            <p className="text-sm text-gray-400">Open Positions</p>
            <p className="text-2xl font-bold text-white">{positions.length}</p>
          </div>
          <div className="bg-gray-800 rounded-xl border border-gray-700 p-4">
            <p className="text-sm text-gray-400">Total Value</p>
            <p className="text-2xl font-bold text-white">₹{totalValue.toFixed(2)}</p>
          </div>
          <div className="bg-gray-800 rounded-xl border border-gray-700 p-4">
            <p className="text-sm text-gray-400">Unrealized P&L</p>
            <p className={`text-2xl font-bold ${totalPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
              ₹{totalPnl.toFixed(2)}
            </p>
          </div>
        </div>

        {loading ? (
          <div className="text-center py-12 text-gray-500">Loading...</div>
        ) : positions.length === 0 ? (
          <div className="text-center py-12">
            <p className="text-gray-400">No open positions</p>
          </div>
        ) : (
          <div className="bg-gray-800 rounded-xl border border-gray-700 overflow-hidden">
            <table className="w-full">
              <thead className="bg-gray-700">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Symbol</th>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Side</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Qty</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Avg Price</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Current</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Value</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">P&L</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-700">
                {positions.map((position) => (
                  <tr key={position.id} className="hover:bg-gray-750">
                    <td className="px-4 py-3 text-white font-medium">{position.symbol}</td>
                    <td className="px-4 py-3">
                      <span className={`font-medium ${
                        position.side === 'LONG' ? 'text-green-400' : 'text-red-400'
                      }`}>
                        {position.side}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-300">{position.quantity}</td>
                    <td className="px-4 py-3 text-right text-gray-300">₹{position.avgPrice?.toFixed(2)}</td>
                    <td className="px-4 py-3 text-right text-gray-300">₹{position.currentPrice?.toFixed(2)}</td>
                    <td className="px-4 py-3 text-right text-gray-300">₹{position.positionValue?.toFixed(2)}</td>
                    <td className={`px-4 py-3 text-right font-medium ${
                      (position.unrealizedPnl || 0) >= 0 ? 'text-green-400' : 'text-red-400'
                    }`}>
                      ₹{Math.abs(position.unrealizedPnl || 0).toFixed(2)}
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

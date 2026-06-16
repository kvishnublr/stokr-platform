import { useState, useEffect } from 'react'
import api from '../services/api'

interface Order {
  id: string
  symbol: string
  side: string
  orderType: string
  quantity: number
  price: number
  filledQuantity: number
  averagePrice: number
  status: string
  createdAt: string
}

export default function OrdersPage() {
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchOrders()
  }, [])

  const fetchOrders = async () => {
    try {
      const response = await api.get('/api/v1/orders')
      setOrders(response.data)
    } catch (error) {
      console.error('Failed to fetch orders:', error)
    } finally {
      setLoading(false)
    }
  }

  const handleCancel = async (id: string) => {
    try {
      await api.post(`/api/v1/orders/${id}/cancel`)
      fetchOrders()
    } catch (error) {
      console.error('Failed to cancel order:', error)
    }
  }

  return (
    <div className="min-h-screen bg-gray-900">
      <header className="bg-gray-800 border-b border-gray-700 p-4">
        <div className="max-w-7xl mx-auto">
          <h1 className="text-xl font-bold text-white">Orders</h1>
        </div>
      </header>

      <main className="max-w-7xl mx-auto p-4">
        {loading ? (
          <div className="text-center py-12 text-gray-500">Loading...</div>
        ) : orders.length === 0 ? (
          <div className="text-center py-12">
            <p className="text-gray-400">No orders yet</p>
          </div>
        ) : (
          <div className="bg-gray-800 rounded-xl border border-gray-700 overflow-hidden">
            <table className="w-full">
              <thead className="bg-gray-700">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Time</th>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Symbol</th>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Side</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Qty</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Price</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Filled</th>
                  <th className="px-4 py-3 text-left text-sm font-medium text-gray-300">Status</th>
                  <th className="px-4 py-3 text-right text-sm font-medium text-gray-300">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-700">
                {orders.map((order) => (
                  <tr key={order.id} className="hover:bg-gray-750">
                    <td className="px-4 py-3 text-gray-400 text-sm">
                      {new Date(order.createdAt).toLocaleString()}
                    </td>
                    <td className="px-4 py-3 text-white font-medium">{order.symbol}</td>
                    <td className="px-4 py-3">
                      <span className={`font-medium ${
                        order.side === 'BUY' ? 'text-green-400' : 'text-red-400'
                      }`}>
                        {order.side}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-gray-300">{order.quantity}</td>
                    <td className="px-4 py-3 text-right text-gray-300">₹{order.price?.toFixed(2) || '-'}</td>
                    <td className="px-4 py-3 text-right text-gray-300">
                      {order.filledQuantity ? `${order.filledQuantity} @ ₹${order.averagePrice?.toFixed(2)}` : '-'}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded text-xs ${
                        order.status === 'FILLED' ? 'bg-green-900/50 text-green-400' :
                        order.status === 'PENDING' ? 'bg-yellow-900/50 text-yellow-400' :
                        order.status === 'CANCELLED' ? 'bg-gray-700 text-gray-400' :
                        'bg-red-900/50 text-red-400'
                      }`}>
                        {order.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      {(order.status === 'PENDING' || order.status === 'SUBMITTED') && (
                        <button
                          onClick={() => handleCancel(order.id)}
                          className="text-red-400 hover:text-red-300 text-sm"
                        >
                          Cancel
                        </button>
                      )}
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

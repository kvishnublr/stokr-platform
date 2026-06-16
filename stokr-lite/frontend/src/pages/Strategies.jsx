import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import client from '../api/client';

export default function Strategies() {
  const { data: strategies, isLoading } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => client.get('/strategies').then((r) => r.data),
  });

  if (isLoading) return <div className="text-gray-500">Loading strategies...</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">Strategy Catalog</h1>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {strategies?.map((s) => (
          <div key={s.id} className="bg-white rounded-lg shadow p-6 hover:shadow-md transition">
            <div className="flex justify-between items-start mb-3">
              <h3 className="text-lg font-semibold">{s.name}</h3>
              <span className={`px-2 py-1 rounded text-xs font-medium ${s.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                {s.enabled ? 'Enabled' : 'Disabled'}
              </span>
            </div>
            <p className="text-sm text-gray-600 mb-4">{s.description}</p>
            <div className="flex items-center gap-2 mb-4">
              <span className="px-2 py-1 bg-blue-50 text-blue-700 rounded text-xs">{s.strategyType}</span>
              <span className="px-2 py-1 bg-purple-50 text-purple-700 rounded text-xs">{s.assetClass}</span>
            </div>
            {s.paramsSchema && (
              <details className="text-xs text-gray-500">
                <summary className="cursor-pointer hover:text-gray-700">View Parameters</summary>
                <pre className="mt-2 bg-gray-50 p-2 rounded overflow-x-auto">{JSON.stringify(JSON.parse(s.paramsSchema), null, 2)}</pre>
              </details>
            )}
            {s.enabled && (
              <Link to="/deployments" className="mt-4 block text-center bg-blue-600 text-white py-2 rounded hover:bg-blue-700 text-sm transition">
                Deploy Strategy
              </Link>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

import React, { useEffect, useState } from "react";
import { cn } from "../../lib/utils";

interface ServiceHealth {
  serviceName: string;
  status: "UP" | "DEGRADED" | "DOWN";
  instances?: number;
  responseTimeMs?: number;
  lastHealthCheck?: string;
  details?: string;
  activeConnections?: number;
  processingQueueDepth?: number;
}

interface HealthData {
  overallStatus: "UP" | "DEGRADED" | "DOWN";
  timestamp: string;
  services: ServiceHealth[];
  infrastructure?: {
    databaseStatus: "UP" | "DOWN" | "SLOW";
    databaseResponseTimeMs: number;
    rabbitmqStatus: "UP" | "DOWN" | "SLOW";
    rabbitmqResponseTimeMs: number;
  };
}

export function ServiceHealthPanel() {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchHealth = async () => {
      try {
        const response = await fetch("/api/v1/admin/health");
        if (!response.ok) throw new Error("Failed to fetch health");
        const data = await response.json();
        setHealth(data);
        setError(null);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    };

    fetchHealth();
    const interval = setInterval(fetchHealth, 10000); // Refresh every 10 seconds
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div className="rounded-2xl border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Service Health</h3>
        <div className="text-sm text-gray-500">Loading...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-2xl border border-red-100 bg-red-50 p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Service Health</h3>
        <div className="text-sm text-red-600">Error: {error}</div>
      </div>
    );
  }

  if (!health) return null;

  const statusColor = {
    UP: "bg-green-50 border-green-100",
    DEGRADED: "bg-yellow-50 border-yellow-100",
    DOWN: "bg-red-50 border-red-100",
  };

  const statusDot = {
    UP: "bg-green-500",
    DEGRADED: "bg-yellow-500",
    DOWN: "bg-red-500",
  };

  const statusText = {
    UP: "text-green-700",
    DEGRADED: "text-yellow-700",
    DOWN: "text-red-700",
  };

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-gray-200 bg-white p-6">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h3 className="text-lg font-bold text-gray-900">Service Health</h3>
            <p className="text-xs text-gray-500 mt-1">
              Last updated: {new Date(health.timestamp).toLocaleTimeString()}
            </p>
          </div>
          <div className={cn("h-4 w-4 rounded-full", statusDot[health.overallStatus])} />
        </div>

        {/* Infrastructure Status */}
        {health.infrastructure && (
          <div className="mb-6 grid grid-cols-2 gap-3">
            <div className="rounded-lg bg-gray-50 p-3">
              <div className="text-xs font-semibold text-gray-500 uppercase">Database</div>
              <div className="mt-2 flex items-center justify-between">
                <span className={cn("text-sm font-bold",
                  health.infrastructure.databaseStatus === "UP" ? "text-green-600" :
                  health.infrastructure.databaseStatus === "SLOW" ? "text-yellow-600" :
                  "text-red-600"
                )}>
                  {health.infrastructure.databaseStatus}
                </span>
                <span className="text-xs text-gray-500">
                  {health.infrastructure.databaseResponseTimeMs}ms
                </span>
              </div>
            </div>

            <div className="rounded-lg bg-gray-50 p-3">
              <div className="text-xs font-semibold text-gray-500 uppercase">RabbitMQ</div>
              <div className="mt-2 flex items-center justify-between">
                <span className={cn("text-sm font-bold",
                  health.infrastructure.rabbitmqStatus === "UP" ? "text-green-600" :
                  health.infrastructure.rabbitmqStatus === "SLOW" ? "text-yellow-600" :
                  "text-red-600"
                )}>
                  {health.infrastructure.rabbitmqStatus}
                </span>
                <span className="text-xs text-gray-500">
                  {health.infrastructure.rabbitmqResponseTimeMs}ms
                </span>
              </div>
            </div>
          </div>
        )}

        {/* Services Grid */}
        <div className="space-y-3">
          {health.services.map((service) => (
            <div
              key={service.serviceName}
              className={cn(
                "rounded-lg border p-4",
                statusColor[service.status]
              )}
            >
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <div className={cn("h-2 w-2 rounded-full", statusDot[service.status])} />
                    <span className="font-semibold text-gray-900">
                      {service.serviceName}
                    </span>
                  </div>
                  <div className="mt-2 grid grid-cols-3 gap-4 text-xs">
                    <div>
                      <div className="text-gray-500">Status</div>
                      <div className={cn("font-bold mt-1", statusText[service.status])}>
                        {service.status}
                      </div>
                    </div>
                    {service.instances !== undefined && (
                      <div>
                        <div className="text-gray-500">Instances</div>
                        <div className="font-bold text-gray-900 mt-1">
                          {service.instances}
                        </div>
                      </div>
                    )}
                    {service.responseTimeMs !== undefined && (
                      <div>
                        <div className="text-gray-500">Response Time</div>
                        <div className="font-bold text-gray-900 mt-1">
                          {service.responseTimeMs}ms
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

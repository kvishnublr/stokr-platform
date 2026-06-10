import React, { useEffect, useState } from "react";
import { cn } from "../../lib/utils";

interface QueueHealth {
  queueName: string;
  status: "HEALTHY" | "WARNING" | "CRITICAL";
  pendingMessages: number;
  consumerCount: number;
  processingRatePerSec: number;
  avgMessageAgeMs: number;
  estimatedClearTimeSeconds: number;
  maxQueueLength: number;
  isBackingUp: boolean;
  deadLetterCount: number;
  lastUpdate: string;
  alertMessage?: string;
}

interface QueuesData {
  queues: QueueHealth[];
}

export function QueueMonitoringPanel() {
  const [queuesData, setQueuesData] = useState<QueuesData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchQueues = async () => {
      try {
        const response = await fetch("/api/v1/admin/health/queues");
        if (!response.ok) throw new Error("Failed to fetch queues");
        const data = await response.json();
        setQueuesData({ queues: data });
        setError(null);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    };

    fetchQueues();
    const interval = setInterval(fetchQueues, 5000); // Refresh every 5 seconds
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div className="rounded-2xl border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Queue Monitoring</h3>
        <div className="text-sm text-gray-500">Loading...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-2xl border border-red-100 bg-red-50 p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Queue Monitoring</h3>
        <div className="text-sm text-red-600">Error: {error}</div>
      </div>
    );
  }

  if (!queuesData) return null;

  const statusColor = {
    HEALTHY: "bg-green-50 border-green-100",
    WARNING: "bg-yellow-50 border-yellow-100",
    CRITICAL: "bg-red-50 border-red-100",
  };

  const statusDot = {
    HEALTHY: "bg-green-500",
    WARNING: "bg-yellow-500",
    CRITICAL: "bg-red-500",
  };

  const mainQueues = queuesData.queues.filter(q => !q.queueName.endsWith(".dlq"));
  const dlQueues = queuesData.queues.filter(q => q.queueName.endsWith(".dlq"));

  return (
    <div className="space-y-5">
      <div className="rounded-2xl border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Message Queues</h3>

        {/* Main Queues */}
        <div className="space-y-3 mb-6">
          <div className="text-xs font-semibold uppercase text-gray-500">Trading Queues</div>
          {mainQueues.map((queue) => (
            <div
              key={queue.queueName}
              className={cn(
                "rounded-lg border p-4",
                statusColor[queue.status]
              )}
            >
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <div className={cn("h-2 w-2 rounded-full", statusDot[queue.status])} />
                  <span className="font-semibold text-gray-900">{queue.queueName}</span>
                </div>
                {queue.isBackingUp && (
                  <span className="text-xs font-bold text-red-600">⚠️ BACKING UP</span>
                )}
              </div>

              <div className="grid grid-cols-2 gap-3 text-xs md:grid-cols-4">
                <div>
                  <div className="text-gray-600">Pending</div>
                  <div className="font-bold text-gray-900 mt-1">{queue.pendingMessages}</div>
                </div>
                <div>
                  <div className="text-gray-600">Consumers</div>
                  <div className="font-bold text-gray-900 mt-1">{queue.consumerCount}</div>
                </div>
                <div>
                  <div className="text-gray-600">Rate</div>
                  <div className="font-bold text-gray-900 mt-1">
                    {queue.processingRatePerSec.toFixed(1)}/sec
                  </div>
                </div>
                <div>
                  <div className="text-gray-600">Est. Clear</div>
                  <div className="font-bold text-gray-900 mt-1">
                    {queue.estimatedClearTimeSeconds}s
                  </div>
                </div>
              </div>

              {/* Progress bar for queue depth */}
              <div className="mt-3">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-xs text-gray-600">Queue Capacity</span>
                  <span className="text-xs text-gray-500">
                    {queue.pendingMessages} / {queue.maxQueueLength}
                  </span>
                </div>
                <div className="h-2 rounded-full bg-gray-200">
                  <div
                    className={cn(
                      "h-2 rounded-full transition-all",
                      queue.status === "CRITICAL"
                        ? "bg-red-500"
                        : queue.status === "WARNING"
                          ? "bg-yellow-500"
                          : "bg-green-500"
                    )}
                    style={{
                      width: `${Math.min(
                        (queue.pendingMessages / queue.maxQueueLength) * 100,
                        100
                      )}%`,
                    }}
                  />
                </div>
              </div>

              {queue.alertMessage && (
                <div className="mt-3 text-xs text-red-600 italic">{queue.alertMessage}</div>
              )}
            </div>
          ))}
        </div>

        {/* Dead Letter Queues */}
        {dlQueues.length > 0 && (
          <div className="space-y-3 border-t border-gray-200 pt-6">
            <div className="text-xs font-semibold uppercase text-gray-500">Dead Letter Queues</div>
            {dlQueues.map((queue) => (
              <div
                key={queue.queueName}
                className={cn(
                  "rounded-lg border p-4",
                  queue.deadLetterCount > 0
                    ? "bg-red-50 border-red-100"
                    : "bg-gray-50 border-gray-200"
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-gray-900">{queue.queueName}</span>
                  <span
                    className={cn(
                      "font-bold",
                      queue.deadLetterCount > 0 ? "text-red-600" : "text-gray-600"
                    )}
                  >
                    {queue.deadLetterCount} messages
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

import { useState, useEffect } from "react";
import { cn } from "../../../lib/utils";
import { toast } from "sonner";

interface ServiceStatus {
  name: string;
  status: "UP" | "DEGRADED" | "DOWN";
  instances: number;
  responseTime: number;
  lastCheck: string;
}

interface InfrastructureStatus {
  rabbitmq: ServiceStatus;
  database: ServiceStatus;
  redis: ServiceStatus;
}

interface ServiceHealthResponse {
  services: ServiceStatus[];
  infrastructure: InfrastructureStatus;
  overallStatus: "UP" | "DEGRADED" | "DOWN";
  lastSync: string;
}

const getStatusColor = (status: "UP" | "DEGRADED" | "DOWN") => {
  switch (status) {
    case "UP":
      return "bg-green-50 border-green-200";
    case "DEGRADED":
      return "bg-yellow-50 border-yellow-200";
    case "DOWN":
      return "bg-red-50 border-red-200";
  }
};

const getStatusBadgeColor = (status: "UP" | "DEGRADED" | "DOWN") => {
  switch (status) {
    case "UP":
      return "bg-green-500";
    case "DEGRADED":
      return "bg-yellow-500";
    case "DOWN":
      return "bg-red-500";
  }
};

const getStatusText = (status: "UP" | "DEGRADED" | "DOWN") => {
  switch (status) {
    case "UP":
      return "text-green-700";
    case "DEGRADED":
      return "text-yellow-700";
    case "DOWN":
      return "text-red-700";
  }
};

export function ServiceHealthPanel() {
  const [health, setHealth] = useState<ServiceHealthResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchHealthStatus = async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await fetch("/api/admin/health");
      if (!response.ok) throw new Error("Failed to fetch health status");
      const data = await response.json();
      setHealth(data);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Unknown error";
      setError(message);
      toast.error("Failed to load service health", { description: message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHealthStatus();
    const interval = setInterval(fetchHealthStatus, 10000); // Refresh every 10 seconds
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <section className="rounded-2xl border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Service Health</h3>
        <div className="rounded-lg bg-gray-50 p-8 text-center text-sm text-gray-500">
          Loading service status...
        </div>
      </section>
    );
  }

  if (error || !health) {
    return (
      <section className="rounded-2xl border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-lg font-bold text-gray-900">Service Health</h3>
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Unable to load service health: {error}
        </div>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-gray-200 bg-white p-6">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-bold text-gray-900">Service Health</h3>
        <div className="flex items-center gap-2">
          <span
            className={cn(
              "h-3 w-3 rounded-full",
              getStatusBadgeColor(health.overallStatus)
            )}
          />
          <span className={cn("text-xs font-semibold", getStatusText(health.overallStatus))}>
            {health.overallStatus === "UP" ? "System Healthy" : "System Issues Detected"}
          </span>
          <span className="text-xs text-gray-500">• {health.lastSync}</span>
        </div>
      </div>

      {/* Microservices */}
      <div className="mb-6">
        <h4 className="mb-3 text-xs font-semibold uppercase tracking-wide text-gray-500">
          Microservices
        </h4>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
          {health.services.map((service) => (
            <div
              key={service.name}
              className={cn(
                "rounded-lg border p-4",
                getStatusColor(service.status)
              )}
            >
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span
                      className={cn(
                        "h-2 w-2 rounded-full",
                        getStatusBadgeColor(service.status)
                      )}
                    />
                    <span className="font-semibold text-gray-900">
                      {service.name.replace(/-/g, " ").toUpperCase()}
                    </span>
                  </div>
                  <div className="mt-2 space-y-1 text-xs text-gray-600">
                    <div>Instances: {service.instances}</div>
                    <div>Response Time: {service.responseTime}ms</div>
                  </div>
                </div>
                <span className={cn("text-xs font-bold", getStatusText(service.status))}>
                  {service.status}
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Infrastructure */}
      <div>
        <h4 className="mb-3 text-xs font-semibold uppercase tracking-wide text-gray-500">
          Infrastructure
        </h4>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
          {[
            { key: "rabbitmq", label: "RabbitMQ", service: health.infrastructure.rabbitmq },
            { key: "database", label: "PostgreSQL", service: health.infrastructure.database },
            { key: "redis", label: "Redis Cache", service: health.infrastructure.redis },
          ].map(({ label, service }) => (
            <div
              key={label}
              className={cn(
                "rounded-lg border p-4",
                getStatusColor(service.status)
              )}
            >
              <div className="flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span
                      className={cn(
                        "h-2 w-2 rounded-full",
                        getStatusBadgeColor(service.status)
                      )}
                    />
                    <span className="font-semibold text-gray-900">{label}</span>
                  </div>
                  <div className="mt-2 text-xs text-gray-600">
                    Response: {service.responseTime}ms
                  </div>
                </div>
                <span className={cn("text-xs font-bold", getStatusText(service.status))}>
                  {service.status}
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

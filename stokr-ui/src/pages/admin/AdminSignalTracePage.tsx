import { useQuery } from "@tanstack/react-query";
import { useParams, Link } from "react-router-dom";
import { api } from "../../api/client";
import { AdminPageShell } from "../../components/admin/institutional/AdminDesignSystem";
import {
  ArrowRight, AlertTriangle, CheckCircle2, XCircle, Clock,
  Loader2, User, Activity, ChevronDown, ChevronUp,
} from "lucide-react";
import { useState } from "react";
import { cn } from "../../lib/utils";

// ─── Types ───────────────────────────────────────────────────────────

type PipelineStage = {
  stage: string;
  status: string;
  label: string;
  timestamp: string | null;
  rejectionCode: string | null;
  rejectionMessage: string | null;
  details: Record<string, unknown> | null;
  orderIndex: number;
};

type UserTrace = {
  userId: string;
  username: string;
  displayName: string;
  finalStatus: string;
  lastStage: string | null;
  lastRejectionCode: string | null;
  lastRejectionMessage: string | null;
  brokerExternalOrderId: string | null;
  brokerOrderId: string | null;
  userStages: PipelineStage[];
};

type TraceData = {
  signalId: string;
  symbol: string;
  strategyKey: string;
  signalType: string;
  executionMode: string;
  outcomeStatus: string | null;
  createdAt: string;
  overallStatus: string;
  applicationPipeline: PipelineStage[];
  users: UserTrace[];
};

// ─── Stage Status Helpers ─────────────────────────────────────────────

function stageColor(status: string): string {
  if (status === "PASSED") return "bg-emerald-500/20 border-emerald-500 text-emerald-300";
  if (status === "FAILED" || status === "BLOCKED") return "bg-red-500/20 border-red-500 text-red-300";
  if (status === "PENDING") return "bg-amber-500/20 border-amber-500 text-amber-300";
  if (status.includes("NO_DATA")) return "bg-zinc-500/20 border-zinc-500 text-zinc-400";
  return "bg-zinc-500/20 border-zinc-500 text-zinc-400";
}

function stageIcon(status: string) {
  if (status === "PASSED") return <CheckCircle2 className="w-4 h-4 text-emerald-400" />;
  if (status === "FAILED" || status === "BLOCKED") return <XCircle className="w-4 h-4 text-red-400" />;
  if (status === "PENDING") return <Clock className="w-4 h-4 text-amber-400" />;
  return <Activity className="w-4 h-4 text-zinc-500" />;
}

function overallBadgeColor(status: string): string {
  if (status === "ALL_FILLED" || status === "PARTIAL_FILL") return "bg-emerald-500/20 text-emerald-300 border-emerald-500";
  if (status === "ALL_REJECTED" || status === "APPLICATION_BLOCKED") return "bg-red-500/20 text-red-300 border-red-500";
  if (status === "PENDING" || status === "NO_USERS") return "bg-amber-500/20 text-amber-300 border-amber-500";
  return "bg-zinc-500/20 text-zinc-300 border-zinc-500";
}

function userStatusBadge(finalStatus: string): string {
  if (finalStatus === "FILLED" || finalStatus === "PARTIAL_FILL") return "bg-emerald-500/15 text-emerald-300";
  if (finalStatus === "REJECTED" || finalStatus === "EXECUTION_REJECTED") return "bg-red-500/15 text-red-300";
  if (finalStatus === "PENDING" || finalStatus === "CREATED" || finalStatus === "VALIDATED" || finalStatus === "RISK_CHECK") return "bg-amber-500/15 text-amber-300";
  return "bg-zinc-500/15 text-zinc-300";
}

// ─── Stage Box Component ──────────────────────────────────────────────

function StageBox({ stage }: { stage: PipelineStage }) {
  const [expanded, setExpanded] = useState(false);
  const hasDetails = stage.rejectionCode || stage.rejectionMessage || (stage.details && Object.keys(stage.details).length > 0);

  return (
    <div
      className={cn(
        "flex flex-col items-center gap-1.5 min-w-[110px] max-w-[140px]",
      )}
    >
      <div
        className={cn(
          "flex flex-col items-center gap-1 px-2.5 py-2 rounded-lg border cursor-pointer transition-colors w-full",
          stageColor(stage.status),
        )}
        onClick={() => hasDetails && setExpanded(!expanded)}
        title={stage.label}
      >
        {stageIcon(stage.status)}
        <span className="text-[10px] font-semibold text-center leading-tight">{stage.label}</span>
        {stage.timestamp && (
          <span className="text-[9px] opacity-60">{new Date(stage.timestamp).toLocaleTimeString("en-IN")}</span>
        )}
        {hasDetails && (
          <ChevronDown className={cn("w-3 h-3 transition-transform", expanded && "rotate-180")} />
        )}
      </div>
      {expanded && hasDetails && (
        <div className="absolute top-full mt-1 z-10 bg-zinc-800 border border-zinc-600 rounded-lg p-2.5 text-[11px] w-64 shadow-xl">
          {stage.rejectionCode && (
            <div className="mb-1.5">
              <span className="font-semibold text-red-300">{stage.rejectionCode}</span>
            </div>
          )}
          {stage.rejectionMessage && (
            <div className="mb-1.5 text-zinc-300 break-words">{stage.rejectionMessage}</div>
          )}
          {stage.details && Object.keys(stage.details).length > 0 && (
            <div className="space-y-0.5">
              {Object.entries(stage.details).map(([k, v]) => (
                <div key={k} className="flex justify-between gap-2">
                  <span className="text-zinc-400">{k}:</span>
                  <span className="text-zinc-200 font-mono text-right max-w-[150px] truncate">
                    {typeof v === "object" ? JSON.stringify(v) : String(v ?? "null")}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ─── Pipeline Flow Component ──────────────────────────────────────────

function PipelineFlow({ stages, title }: { stages: PipelineStage[]; title?: string }) {
  return (
    <div className="relative">
      {title && <h4 className="text-xs font-semibold text-zinc-400 mb-3 uppercase tracking-wide">{title}</h4>}
      <div className="flex items-start gap-0 overflow-x-auto pb-4">
        {stages.map((stage, i) => (
          <div key={stage.stage} className="flex items-start shrink-0 relative">
            <StageBox stage={stage} />
            {i < stages.length - 1 && (
              <div className="flex items-center self-center pt-1 mx-0.5">
                <ArrowRight className="w-4 h-4 text-zinc-600" />
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Main Page Component ──────────────────────────────────────────────

export default function AdminSignalTracePage() {
  const { id } = useParams<{ id: string }>();
  const [expandedUsers, setExpandedUsers] = useState<Record<string, boolean>>({});

  const { data, isLoading, error } = useQuery<TraceData>({
    queryKey: ["signal-pipeline-trace", id],
    queryFn: async () => {
      const res = await api.get(`/api/admin/signals/${id}/pipeline-trace`);
      return res.data.data;
    },
    enabled: !!id,
    refetchInterval: 10_000,
  });

  if (isLoading) {
    return (
      <AdminPageShell title="Pipeline Trace" subtitle="Loading signal trace...">
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 animate-spin text-zinc-500" />
        </div>
      </AdminPageShell>
    );
  }

  if (error || !data) {
    return (
      <AdminPageShell title="Pipeline Trace" subtitle="Error loading trace">
        <div className="flex flex-col items-center py-20 gap-3">
          <AlertTriangle className="w-10 h-10 text-red-400" />
          <p className="text-zinc-400 text-sm">Failed to load pipeline trace for signal {id}</p>
          <Link to="/admin/signals" className="text-blue-400 text-sm hover:underline">← Back to Signal Monitor</Link>
        </div>
      </AdminPageShell>
    );
  }

  return (
    <AdminPageShell
      title={`Pipeline Trace: ${data.symbol}`}
      subtitle={
        <div className="flex items-center gap-3 flex-wrap">
          <span className="text-zinc-400">{data.strategyKey}</span>
          <span className="text-zinc-500">·</span>
          <span className={cn("text-xs px-2 py-0.5 rounded border", overallBadgeColor(data.overallStatus))}>
            {data.overallStatus}
          </span>
          {data.executionMode && (
            <>
              <span className="text-zinc-500">·</span>
              <span className="text-xs px-2 py-0.5 rounded bg-zinc-700 text-zinc-300">{data.executionMode}</span>
            </>
          )}
          <span className="text-zinc-500">·</span>
          <span className="text-xs text-zinc-400">
            {new Date(data.createdAt).toLocaleString("en-IN", { timeZone: "Asia/Kolkata" })}
          </span>
          <Link to={`/admin/signals`} className="text-blue-400 text-xs hover:underline ml-auto">← Signal Monitor</Link>
        </div>
      }
    >
      <div className="space-y-8">
        {/* ── Application Pipeline ── */}
        <div className="bg-zinc-900/50 rounded-xl border border-zinc-800 p-5">
          <div className="flex items-center gap-2 mb-4">
            <Activity className="w-4 h-4 text-zinc-400" />
            <h3 className="text-sm font-semibold text-zinc-200">Application Pipeline</h3>
            <span className="text-[10px] text-zinc-500">(Common — affects all users)</span>
          </div>
          <PipelineFlow stages={data.applicationPipeline} />
        </div>

        {/* ── Per-User Traces ── */}
        <div className="bg-zinc-900/50 rounded-xl border border-zinc-800 p-5">
          <div className="flex items-center gap-2 mb-4">
            <User className="w-4 h-4 text-zinc-400" />
            <h3 className="text-sm font-semibold text-zinc-200">Per-User Execution</h3>
            <span className="text-[10px] text-zinc-500">(Individual trader pipelines)</span>
          </div>

          {data.users.length === 0 ? (
            <div className="text-center py-8 text-zinc-500 text-sm">
              No users received this signal yet.
            </div>
          ) : (
            <div className="space-y-4">
              {data.users.map((user) => {
                const expanded = expandedUsers[user.userId] ?? true;
                return (
                  <div key={user.userId} className="bg-zinc-800/40 rounded-lg border border-zinc-700/50 overflow-hidden">
                    {/* User header */}
                    <div
                      className="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-zinc-700/30 transition-colors"
                      onClick={() => setExpandedUsers(prev => ({ ...prev, [user.userId]: !expanded }))}
                    >
                      <div className="w-7 h-7 rounded-full bg-zinc-700 flex items-center justify-center">
                        <User className="w-3.5 h-3.5 text-zinc-300" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="text-sm font-medium text-zinc-200 truncate">
                          {user.displayName}
                        </div>
                        <div className="text-[10px] text-zinc-500">@{user.username}</div>
                      </div>
                      <div className="flex items-center gap-2">
                        {user.brokerExternalOrderId && (
                          <span className="text-[10px] text-zinc-400 font-mono" title="Broker Order ID">
                            #{user.brokerExternalOrderId}
                          </span>
                        )}
                        <span className={cn(
                          "text-[10px] px-2 py-0.5 rounded font-medium",
                          userStatusBadge(user.finalStatus)
                        )}>
                          {user.finalStatus}
                        </span>
                        {expanded ? <ChevronUp className="w-3.5 h-3.5 text-zinc-500" /> : <ChevronDown className="w-3.5 h-3.5 text-zinc-500" />}
                      </div>
                    </div>

                    {/* User pipeline */}
                    {expanded && (
                      <div className="px-4 pb-4 pt-1 border-t border-zinc-700/30">
                        {user.userStages.length === 0 ? (
                          <div className="text-center py-4 text-zinc-500 text-xs">
                            {user.lastRejectionCode
                              ? `Blocked: ${user.lastRejectionCode}`
                              : "Waiting for OMS processing..."}
                          </div>
                        ) : (
                          <PipelineFlow stages={user.userStages} />
                        )}
                        {/* Rejection detail banner */}
                        {user.lastRejectionCode && (
                          <div className="mt-2 bg-red-500/10 border border-red-500/30 rounded-lg p-2.5">
                            <div className="flex items-start gap-2">
                              <XCircle className="w-4 h-4 text-red-400 shrink-0 mt-0.5" />
                              <div>
                                <span className="text-xs font-semibold text-red-300">{user.lastRejectionCode}</span>
                                {user.lastRejectionMessage && (
                                  <p className="text-xs text-zinc-300 mt-0.5">{user.lastRejectionMessage}</p>
                                )}
                              </div>
                            </div>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </AdminPageShell>
  );
}

import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { AdminPageShell, AdminPanel } from "../components/admin/institutional/AdminDesignSystem";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

type UserRow = {
  id: string;
  username: string;
  email: string;
  displayName: string | null;
  enabled: boolean;
  roles: string[];
  createdAt: string;
  lastLoginAt: string | null;
  activeStrategies: number;
  brokerLinked: boolean;
  liveTradingApproved: boolean;
};

type PageWrap = {
  content: UserRow[];
  totalElements: number;
  totalPages: number;
  page: number;
};

function normalizeUserRow(raw: Record<string, unknown>): UserRow | null {
  const id = raw.id != null ? String(raw.id) : null;
  const username = typeof raw.username === "string" ? raw.username : null;
  const email = typeof raw.email === "string" ? raw.email : null;
  if (!id || !username || !email) return null;
  let roles: string[] = [];
  if (Array.isArray(raw.roles)) {
    roles = raw.roles.filter((r): r is string => typeof r === "string");
  }
  return {
    id,
    username,
    email,
    displayName: typeof raw.displayName === "string" ? raw.displayName : null,
    enabled: Boolean(raw.enabled),
    roles,
    createdAt: typeof raw.createdAt === "string" ? raw.createdAt : "",
    lastLoginAt: typeof raw.lastLoginAt === "string" ? raw.lastLoginAt : null,
    activeStrategies: typeof raw.activeStrategies === "number" ? raw.activeStrategies : Number(raw.activeStrategies) || 0,
    brokerLinked: Boolean(raw.brokerLinked),
    liveTradingApproved: Boolean(raw.liveTradingApproved),
  };
}

export function AdminUsersPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [enabled, setEnabled] = useState<boolean | "">("");
  const [page, setPage] = useState(0);

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedSearch(search.trim()), 320);
    return () => window.clearTimeout(t);
  }, [search]);

  const params = useMemo(() => {
    const p = new URLSearchParams();
    p.set("page", String(page));
    p.set("size", "15");
    if (debouncedSearch) p.set("search", debouncedSearch);
    if (enabled !== "") p.set("enabled", String(enabled));
    return p.toString();
  }, [debouncedSearch, enabled, page]);

  const q = useQuery({
    queryKey: ["admin-users", params],
    queryFn: async () => {
      const res = await api.get(`/api/admin/users?${params}`);
      const raw = res.data?.data as Record<string, unknown> | undefined;
      if (!raw || !Array.isArray(raw.content)) {
        return {
          content: [] as UserRow[],
          totalElements: 0,
          totalPages: 0,
          page: 0,
        } satisfies PageWrap;
      }
      const rows = (raw.content as Record<string, unknown>[])
        .map((r) => normalizeUserRow(r))
        .filter((r): r is UserRow => r != null);
      return {
        content: rows,
        totalElements: Number(raw.totalElements ?? rows.length) || 0,
        totalPages: Number(raw.totalPages ?? 0) || 0,
        page: Number(raw.page ?? 0) || 0,
      } satisfies PageWrap;
    },
  });

  const data = q.data;
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const canPrev = page > 0;
  const canNext = data != null && data.totalPages > 0 && page < data.totalPages - 1;

  async function toggleStatus(u: UserRow, next: boolean) {
    try {
      await api.patch(`/api/admin/users/${u.id}/status`, { enabled: next });
      toast.success(next ? "User enabled" : "User disabled");
      void q.refetch();
    } catch (e) {
      toast.error(parseAxiosMessage(e));
    }
  }

  async function resetPw(u: UserRow) {
    try {
      const res = await api.patch(`/api/admin/users/${u.id}/reset-password`);
      const temp = res.data?.data?.temporaryPassword as string | undefined;
      toast.success(temp ? `Temporary password: ${temp}` : "Password reset issued");
    } catch (e) {
      toast.error(parseAxiosMessage(e));
    }
  }

  async function toggleLiveApproval(u: UserRow, approve: boolean) {
    try {
      await api.patch(`/api/admin/users/${u.id}/live-trading`, { liveTradingApproved: approve });
      toast.success(approve ? `Live trading approved for ${u.username}` : `Live trading revoked for ${u.username}`);
      void q.refetch();
    } catch (e) {
      toast.error(parseAxiosMessage(e));
    }
  }

  const chrome = isLight
    ? "border-neutral-200 bg-neutral-100 text-neutral-900"
    : "border-neutral-800 bg-neutral-900/80 text-neutral-200";
  const inputCls = isLight
    ? "border-neutral-200 bg-white text-neutral-900 placeholder:text-neutral-400 focus:border-blue-500/50"
    : "border-neutral-800 bg-neutral-950 text-white placeholder:text-neutral-500 focus:border-blue-500/40";
  const selectCls = isLight
    ? "border-neutral-200 bg-white text-neutral-900"
    : "border-neutral-800 bg-neutral-950 text-white";
  const thCls = isLight ? "text-neutral-500" : "text-neutral-500";
  const rowCls = isLight
    ? "bg-white hover:bg-neutral-50/90 hover:shadow-[inset_3px_0_0_0] hover:shadow-blue-500/40"
    : "bg-neutral-950/40 hover:bg-neutral-900/40 hover:shadow-[inset_3px_0_0_0] hover:shadow-blue-400/50";
  const divideCls = isLight ? "divide-neutral-200" : "divide-neutral-800";

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Access control"
      title="User management"
      subtitle="Search accounts, change status, approve live trading, or issue a temporary password."
      alert={
        q.isError ? (
          <div
            className={cn(
              "flex flex-col gap-3 rounded-xl border px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between",
              isLight ? "border-red-200 bg-red-50 text-red-900" : "border-red-900/40 bg-red-950/30 text-red-200",
            )}
          >
            <span>Could not load users. {parseAxiosMessage(q.error)}</span>
            <button
              type="button"
              onClick={() => void q.refetch()}
              className={cn(
                "shrink-0 rounded-lg border px-3 py-1.5 text-xs font-semibold transition",
                isLight ? "border-red-300 bg-white text-red-900 hover:bg-red-100" : "border-red-800/60 text-red-100 hover:bg-red-950/50",
              )}
            >
              Retry
            </button>
          </div>
        ) : null
      }
    >
      <AdminPanel isLight={isLight} noPadding className="overflow-hidden shadow-sm">
        <div className={cn("flex flex-wrap items-end gap-3 border-b p-4", chrome)}>
          <div className="min-w-[200px] flex-1">
            <label className={cn("text-xs font-medium uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-500")}>
              Search
            </label>
            <div className="relative mt-1">
              <Search
                className={cn(
                  "pointer-events-none absolute left-2.5 top-2.5 h-4 w-4",
                  isLight ? "text-neutral-400" : "text-neutral-500",
                )}
              />
              <input
                className={cn("w-full rounded-lg border py-2 pl-9 pr-3 text-sm outline-none ring-0", inputCls)}
                placeholder="Email, username, name..."
                value={search}
                onChange={(e) => {
                  setPage(0);
                  setSearch(e.target.value);
                }}
              />
            </div>
          </div>
          <div>
            <label className={cn("text-xs font-medium uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-500")}>
              Status
            </label>
            <select
              className={cn("mt-1 rounded-lg border px-3 py-2 text-sm outline-none", selectCls)}
              value={enabled === "" ? "" : enabled ? "true" : "false"}
              onChange={(e) => {
                setPage(0);
                const v = e.target.value;
                setEnabled(v === "" ? "" : v === "true");
              }}
            >
              <option value="">Any</option>
              <option value="true">Enabled</option>
              <option value="false">Disabled</option>
            </select>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] border-collapse text-left text-sm">
            <thead>
              <tr className={cn("border-b text-xs uppercase tracking-wide", chrome, isLight ? "border-neutral-200" : "border-neutral-800")}>
                <th className={cn("px-4 py-3 font-semibold", thCls)}>User</th>
                <th className={cn("px-4 py-3 font-semibold", thCls)}>Roles</th>
                <th className={cn("px-4 py-3 font-semibold", thCls)}>Strategies</th>
                <th className={cn("px-4 py-3 font-semibold", thCls)}>Broker</th>
                <th className={cn("px-4 py-3 font-semibold", thCls)}>Status</th>
                <th className={cn("px-4 py-3 font-semibold", thCls)}>Live Trading</th>
                <th className={cn("px-4 py-3 text-right font-semibold", thCls)}>Actions</th>
              </tr>
            </thead>
            <tbody className={cn("divide-y", divideCls)}>
              {q.isLoading ? (
                <tr>
                  <td colSpan={7} className={cn("px-4 py-10 text-center text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>
                    Loading users...
                  </td>
                </tr>
              ) : q.isError ? (
                <tr>
                  <td colSpan={7} className={cn("px-4 py-10 text-center text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>
                    Fix the error above, then use Retry or refresh the page.
                  </td>
                </tr>
              ) : (data?.content ?? []).length === 0 ? (
                <tr>
                  <td colSpan={7} className={cn("px-4 py-10 text-center text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>
                    No users match the current filters. Try clearing search or set status to &quot;Any&quot;.
                  </td>
                </tr>
              ) : (
                (data?.content ?? []).map((u) => (
                  <tr key={u.id} className={rowCls}>
                    <td className="px-4 py-3">
                      <div className={cn("font-medium", isLight ? "text-neutral-900" : "text-white")}>{u.username}</div>
                      <div className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>{u.email}</div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        {(u.roles ?? []).map((r) => (
                          <span
                            key={r}
                            className={cn(
                              "rounded-md px-2 py-0.5 text-[10px] font-medium",
                              isLight ? "bg-neutral-200 text-neutral-800" : "bg-neutral-800 text-neutral-300",
                            )}
                          >
                            {r.replace("ROLE_", "")}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className={cn("px-4 py-3", isLight ? "text-neutral-700" : "text-neutral-300")}>{u.activeStrategies}</td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          "rounded-full px-2 py-0.5 text-[11px] font-semibold",
                          u.brokerLinked
                            ? isLight
                              ? "bg-emerald-100 text-emerald-800"
                              : "bg-emerald-950 text-emerald-300"
                            : isLight
                              ? "bg-neutral-100 text-neutral-500"
                              : "bg-neutral-800 text-neutral-500",
                        )}
                      >
                        {u.brokerLinked ? "Linked" : "None"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          "rounded-full px-2 py-0.5 text-[11px] font-semibold",
                          u.enabled
                            ? isLight
                              ? "bg-blue-100 text-blue-800"
                              : "bg-blue-950 text-blue-300"
                            : isLight
                              ? "bg-red-100 text-red-800"
                              : "bg-red-950 text-red-300",
                        )}
                      >
                        {u.enabled ? "Active" : "Disabled"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn(
                        "rounded-full px-2 py-0.5 text-[11px] font-semibold",
                        u.liveTradingApproved
                          ? isLight ? "bg-emerald-100 text-emerald-800" : "bg-emerald-950 text-emerald-300"
                          : isLight ? "bg-amber-100 text-amber-800" : "bg-amber-950 text-amber-400",
                      )}>
                        {u.liveTradingApproved ? "Approved" : "Pending"}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex justify-end gap-2">
                        <button
                          type="button"
                          className={cn(
                            "rounded-md border px-2 py-1 text-xs font-semibold transition",
                            u.liveTradingApproved
                              ? isLight ? "border-amber-300 text-amber-800 hover:bg-amber-50" : "border-amber-700/60 text-amber-300 hover:bg-amber-950/40"
                              : isLight ? "border-emerald-300 text-emerald-800 hover:bg-emerald-50" : "border-emerald-700/60 text-emerald-300 hover:bg-emerald-950/40",
                          )}
                          onClick={() => void toggleLiveApproval(u, !u.liveTradingApproved)}
                        >
                          {u.liveTradingApproved ? "Revoke Live" : "Approve Live"}
                        </button>
                        <button
                          type="button"
                          className={cn(
                            "rounded-md border px-2 py-1 text-xs transition",
                            isLight
                              ? "border-neutral-300 text-neutral-800 hover:bg-neutral-100"
                              : "border-neutral-700 text-neutral-200 hover:bg-neutral-800",
                          )}
                          onClick={() => toggleStatus(u, !u.enabled)}
                        >
                          {u.enabled ? "Disable" : "Enable"}
                        </button>
                        <button
                          type="button"
                          className={cn(
                            "rounded-md px-2 py-1 text-xs font-semibold transition",
                            isLight ? "bg-neutral-900 text-white hover:bg-neutral-800" : "bg-white text-neutral-950 hover:bg-neutral-200",
                          )}
                          onClick={() => resetPw(u)}
                        >
                          Reset PW
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </AdminPanel>

      <div className={cn("flex flex-wrap items-center justify-between gap-3 text-sm", isLight ? "text-neutral-600" : "text-neutral-500")}>
        <span>
          Page {page + 1} / {totalPages}  ·  {data?.totalElements ?? 0} users
        </span>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={!canPrev || q.isLoading}
            className={cn(
              "rounded-md border px-3 py-1 text-xs font-medium transition disabled:opacity-40",
              isLight
                ? "border-neutral-300 bg-white text-neutral-800 hover:bg-neutral-50"
                : "border-neutral-800 text-neutral-200 hover:bg-neutral-900",
            )}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            Prev
          </button>
          <button
            type="button"
            disabled={!canNext || q.isLoading}
            className={cn(
              "rounded-md border px-3 py-1 text-xs font-medium transition disabled:opacity-40",
              isLight
                ? "border-neutral-300 bg-white text-neutral-800 hover:bg-neutral-50"
                : "border-neutral-800 text-neutral-200 hover:bg-neutral-900",
            )}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      </div>
    </AdminPageShell>
  );
}

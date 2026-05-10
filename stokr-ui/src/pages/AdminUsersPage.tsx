import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";

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
};

type PageWrap = {
  content: UserRow[];
  totalElements: number;
  totalPages: number;
  page: number;
};

export function AdminUsersPage() {
  const [search, setSearch] = useState("");
  const [enabled, setEnabled] = useState<boolean | "">("");
  const [page, setPage] = useState(0);

  const params = useMemo(() => {
    const p = new URLSearchParams();
    p.set("page", String(page));
    p.set("size", "15");
    if (search.trim()) p.set("search", search.trim());
    if (enabled !== "") p.set("enabled", String(enabled));
    return p.toString();
  }, [search, enabled, page]);

  const q = useQuery({
    queryKey: ["admin-users", params],
    queryFn: async () => {
      const res = await api.get(`/api/admin/users?${params}`);
      return res.data?.data as PageWrap;
    },
  });

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

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">User management</h1>
        <p className="mt-2 text-sm text-neutral-400">Search accounts, change status, or issue a temporary password.</p>
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-xl border border-neutral-800 bg-neutral-900/40 p-4">
        <div className="min-w-[200px] flex-1">
          <label className="text-xs text-neutral-500">Search</label>
          <div className="relative mt-1">
            <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-neutral-500" />
            <input
              className="w-full rounded-lg border border-neutral-800 bg-neutral-950 py-2 pl-9 pr-3 text-sm text-white outline-none focus:border-blue-500/40"
              placeholder="Email, username, name…"
              value={search}
              onChange={(e) => {
                setPage(0);
                setSearch(e.target.value);
              }}
            />
          </div>
        </div>
        <div>
          <label className="text-xs text-neutral-500">Status</label>
          <select
            className="mt-1 rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white"
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

      <div className="overflow-hidden rounded-xl border border-neutral-800">
        <table className="w-full border-collapse text-left text-sm">
          <thead className="bg-neutral-900/80 text-xs uppercase tracking-wide text-neutral-500">
            <tr>
              <th className="px-4 py-3 font-medium">User</th>
              <th className="px-4 py-3 font-medium">Roles</th>
              <th className="px-4 py-3 font-medium">Strategies</th>
              <th className="px-4 py-3 font-medium">Broker</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-800">
            {(q.data?.content ?? []).map((u) => (
              <tr key={u.id} className="bg-neutral-950/40 hover:bg-neutral-900/40">
                <td className="px-4 py-3">
                  <div className="font-medium text-white">{u.username}</div>
                  <div className="text-xs text-neutral-500">{u.email}</div>
                </td>
                <td className="px-4 py-3">
                  <div className="flex flex-wrap gap-1">
                    {u.roles.map((r) => (
                      <span
                        key={r}
                        className="rounded-md bg-neutral-800 px-2 py-0.5 text-[10px] font-medium text-neutral-300"
                      >
                        {r.replace("ROLE_", "")}
                      </span>
                    ))}
                  </div>
                </td>
                <td className="px-4 py-3 text-neutral-300">{u.activeStrategies}</td>
                <td className="px-4 py-3">
                  <span
                    className={cn(
                      "rounded-full px-2 py-0.5 text-[11px] font-semibold",
                      u.brokerLinked ? "bg-emerald-950 text-emerald-300" : "bg-neutral-800 text-neutral-500",
                    )}
                  >
                    {u.brokerLinked ? "Linked" : "None"}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <span
                    className={cn(
                      "rounded-full px-2 py-0.5 text-[11px] font-semibold",
                      u.enabled ? "bg-blue-950 text-blue-300" : "bg-red-950 text-red-300",
                    )}
                  >
                    {u.enabled ? "Active" : "Disabled"}
                  </span>
                </td>
                <td className="px-4 py-3 text-right">
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      className="rounded-md border border-neutral-700 px-2 py-1 text-xs text-neutral-200 hover:bg-neutral-800"
                      onClick={() => toggleStatus(u, !u.enabled)}
                    >
                      {u.enabled ? "Disable" : "Enable"}
                    </button>
                    <button
                      type="button"
                      className="rounded-md bg-white px-2 py-1 text-xs font-semibold text-neutral-950 hover:bg-neutral-200"
                      onClick={() => resetPw(u)}
                    >
                      Reset PW
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between text-sm text-neutral-500">
        <span>
          Page {(q.data?.page ?? 0) + 1} / {Math.max(1, q.data?.totalPages ?? 1)} · {q.data?.totalElements ?? 0}{" "}
          users
        </span>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={page <= 0}
            className="rounded-md border border-neutral-800 px-3 py-1 disabled:opacity-40"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            Prev
          </button>
          <button
            type="button"
            disabled={q.data != null && page >= (q.data.totalPages ?? 1) - 1}
            className="rounded-md border border-neutral-800 px-3 py-1 disabled:opacity-40"
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}

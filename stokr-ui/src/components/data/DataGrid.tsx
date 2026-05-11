import {
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  type ColumnDef,
  type SortingState,
  useReactTable,
} from "@tanstack/react-table";
import { useMemo, useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";

type Props<T> = {
  columns: ColumnDef<T, unknown>[];
  data: T[];
  isLoading?: boolean;
  emptyLabel?: string;
  variant?: "auto" | "light" | "dark";
};

export function DataGrid<T>({ columns, data, isLoading, emptyLabel, variant = "auto" }: Props<T>) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const isLightTheme = useUiThemeStore((s) => s.mode === "light");
  const isLight = variant === "light" || (variant === "auto" && isLightTheme);
  const cols = useMemo(() => columns, [columns]);
  const table = useReactTable({
    data,
    columns: cols,
    state: { sorting },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  return (
    <div
      className={cn(
        "overflow-x-auto rounded-xl border",
        isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/40",
      )}
    >
      <table className="w-full min-w-[720px] border-collapse text-left text-xs">
        <thead className={cn("sticky top-0 z-10 backdrop-blur", isLight ? "bg-white/95" : "bg-neutral-950/95")}>
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id} className={cn("border-b", isLight ? "border-neutral-200" : "border-neutral-800")}>
              {hg.headers.map((h) => (
                <th
                  key={h.id}
                  className={cn(
                    "whitespace-nowrap px-3 py-2 font-semibold uppercase tracking-wide",
                    isLight ? "text-neutral-500" : "text-neutral-500",
                    h.column.getCanSort() && (isLight ? "cursor-pointer select-none hover:text-neutral-700" : "cursor-pointer select-none hover:text-neutral-300"),
                  )}
                  onClick={h.column.getToggleSortingHandler()}
                >
                  <span className="inline-flex items-center gap-1">
                    {flexRender(h.column.columnDef.header, h.getContext())}
                    {h.column.getIsSorted() === "asc" ? (
                      <ChevronUp className="h-3 w-3" />
                    ) : h.column.getIsSorted() === "desc" ? (
                      <ChevronDown className="h-3 w-3" />
                    ) : null}
                  </span>
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody>
          {isLoading ? (
            <tr>
              <td colSpan={columns.length} className={cn("px-3 py-10 text-center", isLight ? "text-neutral-500" : "text-neutral-500")}>
                Loading…
              </td>
            </tr>
          ) : table.getRowModel().rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className={cn("px-3 py-10 text-center", isLight ? "text-neutral-500" : "text-neutral-500")}>
                {emptyLabel ?? "No rows"}
              </td>
            </tr>
          ) : (
            table.getRowModel().rows.map((row) => (
              <tr key={row.id} className={cn("border-b", isLight ? "border-neutral-100 hover:bg-neutral-50" : "border-neutral-900/80 hover:bg-neutral-900/40")}>
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className={cn("whitespace-nowrap px-3 py-2", isLight ? "text-neutral-700" : "text-neutral-200")}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

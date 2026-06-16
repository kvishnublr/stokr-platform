import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { X } from "lucide-react";
import { searchSymbols } from "../../api/strategyCatalog";
import { cn } from "../../lib/utils";

interface Props {
  value: string[];
  onChange: (v: string[]) => void;
  placeholder?: string;
}

export function SymbolMultiSelect({ value, onChange, placeholder = "Search symbols…" }: Props) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const { data: results = [] } = useQuery({
    queryKey: ["symbol-search", query],
    queryFn: () => searchSymbols(query, 30),
    enabled: open,
    staleTime: 30_000,
  });

  // close on outside click
  useEffect(() => {
    function handle(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handle);
    return () => document.removeEventListener("mousedown", handle);
  }, []);

  const filtered = results.filter((s) => !value.includes(s));

  function add(symbol: string) {
    onChange([...value, symbol]);
    setQuery("");
  }

  function remove(symbol: string) {
    onChange(value.filter((s) => s !== symbol));
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" && query.trim()) {
      const upper = query.trim().toUpperCase();
      if (!value.includes(upper)) onChange([...value, upper]);
      setQuery("");
      e.preventDefault();
    }
    if (e.key === "Backspace" && !query && value.length) {
      onChange(value.slice(0, -1));
    }
  }

  return (
    <div ref={containerRef} className="relative">
      {/* Selected chips + input */}
      <div
        className={cn(
          "flex min-h-[38px] flex-wrap gap-1.5 rounded-lg border border-border bg-background px-2.5 py-1.5 cursor-text",
          open && "ring-1 ring-violet-500",
        )}
        onClick={() => { setOpen(true); (containerRef.current?.querySelector("input") as HTMLInputElement)?.focus(); }}
      >
        {value.map((sym) => (
          <span
            key={sym}
            className="flex items-center gap-1 rounded-md bg-violet-500/15 px-2 py-0.5 text-[11px] font-semibold text-violet-600 dark:text-violet-300"
          >
            {sym}
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); remove(sym); }}
              className="text-violet-400 hover:text-violet-600"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        ))}
        <input
          className="min-w-[120px] flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground outline-none"
          placeholder={value.length === 0 ? placeholder : ""}
          value={query}
          onChange={(e) => { setQuery(e.target.value.toUpperCase()); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onKeyDown={handleKeyDown}
        />
      </div>

      {/* Dropdown */}
      {open && (
        <div className="absolute z-50 mt-1 w-full rounded-lg border border-border bg-card shadow-lg">
          {filtered.length === 0 && query.trim() ? (
            <div className="px-3 py-2 text-xs text-muted-foreground">
              Press Enter to add <span className="font-semibold text-foreground">{query.trim().toUpperCase()}</span> manually
            </div>
          ) : filtered.length === 0 ? (
            <div className="px-3 py-2 text-xs text-muted-foreground">
              Type to search symbols from your universe groups
            </div>
          ) : (
            <ul className="max-h-52 overflow-y-auto py-1">
              {filtered.map((sym) => (
                <li key={sym}>
                  <button
                    type="button"
                    className="w-full px-3 py-1.5 text-left text-sm font-mono text-foreground hover:bg-muted"
                    onClick={() => add(sym)}
                  >
                    {sym}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

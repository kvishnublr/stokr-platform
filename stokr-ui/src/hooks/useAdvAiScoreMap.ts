import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchAdvTerminal } from "../api/advDashboard";
import { buildAdvAiScoreMap } from "../lib/confirmationRank";

/**
 * Optional ADV scanner scores for confirmation alignment (same symbol).
 * Fails silently — intraday ranking still works from confidence + RR alone.
 */
export function useAdvAiScoreMap(enabled = true) {
  const q = useQuery({
    queryKey: ["confirmation-adv-scores"],
    queryFn: fetchAdvTerminal,
    enabled,
    staleTime: 20_000,
    refetchInterval: 30_000,
    retry: 1,
  });

  const map = useMemo(
    () => buildAdvAiScoreMap(q.data?.scannerRows),
    [q.data?.scannerRows],
  );

  return {
    advMap: map,
    isLoading: q.isLoading,
    isAligned: map.size > 0,
    partial: q.isError && map.size === 0,
  };
}

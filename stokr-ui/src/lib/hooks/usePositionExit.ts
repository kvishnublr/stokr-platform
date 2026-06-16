import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../../api/client";
import { invalidateBrokerPositionQueries } from "./useBrokerPositionSync";

export function usePositionExit() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (symbol: string) => {
      const action = `EXIT_SYMBOL:${symbol.trim().toUpperCase()}`;
      const preview = (await api.get(`/api/trader/terminal/control/preview?action=${encodeURIComponent(action)}`)).data?.data;
      if (!preview?.supported) {
        throw new Error(String(preview?.reason ?? "Exit not supported for this symbol"));
      }
      const token = String(preview.confirmationToken ?? "");
      if (!token) throw new Error("Missing confirmation token");
      const exec = (await api.post("/api/trader/terminal/control/execute", {
        action,
        confirmationToken: token,
      })).data?.data;
      return exec as Record<string, unknown>;
    },
    onSuccess: (_res, symbol) => {
      toast.success(`Exit order placed for ${symbol.replace(/^NSE:/, "")}`);
      invalidateBrokerPositionQueries(queryClient);
    },
    onError: (err: unknown) => {
      toast.error(parseAxiosMessage(err));
    },
  });
}

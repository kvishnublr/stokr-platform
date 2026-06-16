export type ExecutionRiskControls = {
  reconciliationWarnings?: string[];
  parityState?: string;
  tokenValid?: boolean;
  brokerHealth?: string;
  liveEligible?: boolean;
};

export type ExecutionRiskAssessment = {
  blocked: boolean;
  reasons: string[];
  severity: "none" | "warn" | "block";
};

/** Decide when the terminal should show an execution blocker banner. */
export function assessExecutionRisk(input: {
  risk?: ExecutionRiskControls;
  executionMode?: string | null;
  brokerConnected?: boolean;
}): ExecutionRiskAssessment {
  const reasons: string[] = [];
  const risk = input.risk;
  if (!risk) {
    return { blocked: false, reasons, severity: "none" };
  }

  const mode = String(input.executionMode ?? "").toUpperCase();
  const liveMode = mode === "LIVE";
  const brokerConnected = Boolean(input.brokerConnected);
  const warnings = risk.reconciliationWarnings ?? [];

  if (liveMode && brokerConnected && risk.tokenValid === false) {
    reasons.push("Zerodha session token expired — reconnect broker before placing live orders.");
  }

  if (liveMode && brokerConnected && String(risk.parityState ?? "").toUpperCase() === "MISMATCH") {
    reasons.push("Broker position parity drift detected — reconcile Zerodha vs OMS before new live entries.");
  }

  for (const warning of warnings) {
    if (warning === "ORPHAN_EXECUTIONS") {
      reasons.push("OMS ledger has orphan execution rows — review executions before trading.");
      continue;
    }
    if (warning === "BROKER_POSITION_DRIFT") {
      if (!reasons.some((r) => r.includes("parity drift"))) {
        reasons.push("Broker position parity drift detected — reconcile Zerodha vs OMS before new live entries.");
      }
      continue;
    }
    if (warning.startsWith("SYMBOL_QTY_MISMATCH:")) {
      const symbol = warning.slice("SYMBOL_QTY_MISMATCH:".length) || "symbol";
      reasons.push(`Ledger drift on ${symbol} — execution net qty does not match portfolio position.`);
    }
  }

  const unique = Array.from(new Set(reasons));
  if (unique.length === 0) {
    return { blocked: false, reasons: unique, severity: "none" };
  }

  const blocksLive = liveMode && unique.some((r) =>
    r.includes("token") || r.includes("parity drift") || r.includes("Ledger drift"),
  );
  return {
    blocked: blocksLive || unique.some((r) => r.includes("orphan")),
    reasons: unique,
    severity: blocksLive ? "block" : "warn",
  };
}

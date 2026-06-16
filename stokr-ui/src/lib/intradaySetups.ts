export type IntradaySetupKey =
  | "GAP_FILLS"
  | "VWAP_BOUNCES"
  | "SECTOR_LAGGARDS"
  | "EARLY_BREAKOUTS";

export type IntradaySetupConfig = {
  key: IntradaySetupKey;
  title: string;
  strategyKey: string;
  backtestStrategyKeys?: string[];
  bestWindow: string;
  accuracy: string;
  note: string;
};

export const INTRADAY_SETUPS: IntradaySetupConfig[] = [
  {
    key: "GAP_FILLS",
    title: "Gap Fills",
    strategyKey: "GAP_FILL",
    backtestStrategyKeys: ["GAP_FILL"],
    bestWindow: "09:15-09:45 IST",
    accuracy: "82%",
    note: "Data-driven gap fill toward previous close. Pure price structure, no indicators.",
  },
  {
    key: "VWAP_BOUNCES",
    title: "VWAP Bounces",
    strategyKey: "VWAP_BOUNCE",
    backtestStrategyKeys: ["VWAP_BOUNCE"],
    bestWindow: "09:30-15:15 IST",
    accuracy: "71%",
    note: "Institutional VWAP touch-and-bounce with trend-aligned slope confirmation.",
  },
  {
    key: "SECTOR_LAGGARDS",
    title: "Sector Laggards",
    strategyKey: "SECTOR_LAGGARD",
    backtestStrategyKeys: ["SECTOR_LAGGARD"],
    bestWindow: "10:00-14:30 IST",
    accuracy: "73%",
    note: "Relative strength rotation — laggard stocks catching up to strong sector moves.",
  },
  {
    key: "EARLY_BREAKOUTS",
    title: "Early Breakouts",
    strategyKey: "EARLY_BREAKOUT",
    backtestStrategyKeys: ["EARLY_BREAKOUT"],
    bestWindow: "09:45-11:30 IST",
    accuracy: "68%",
    note: "Opening range breakout with compression, volume, and false-breakout filters.",
  },
];

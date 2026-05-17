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
  note: string;
};

export const INTRADAY_SETUPS: IntradaySetupConfig[] = [
  {
    key: "GAP_FILLS",
    title: "Gap Fills",
    strategyKey: "MEAN_REVERSION_RANGE_FADE",
    backtestStrategyKeys: ["GAP_FILLS", "GAP_FILL", "MEAN_REVERSION_RANGE_FADE"],
    bestWindow: "09:20-10:15 IST",
    note: "Opening dislocation mean reversion.",
  },
  {
    key: "VWAP_BOUNCES",
    title: "VWAP Bounces",
    strategyKey: "VWAP_MEAN_REVERSION",
    backtestStrategyKeys: ["VWAP_BOUNCES", "VWAP_MEAN_REVERSION"],
    bestWindow: "10:30-14:00 IST",
    note: "VWAP stretch and pullback entries.",
  },
  {
    key: "SECTOR_LAGGARDS",
    title: "Sector Laggards",
    strategyKey: "MEAN_REVERSION_V2",
    backtestStrategyKeys: ["SECTOR_LAGGARDS", "MEAN_REVERSION_V2"],
    bestWindow: "11:00-14:45 IST",
    note: "Relative lag catch-up in strong sectors.",
  },
  {
    key: "EARLY_BREAKOUTS",
    title: "Early Breakouts",
    strategyKey: "OPENING_RANGE_BREAKOUT",
    backtestStrategyKeys: ["EARLY_BREAKOUTS", "OPENING_RANGE_BREAKOUT", "MOMENTUM_BREAKOUT"],
    bestWindow: "09:20-10:30 IST",
    note: "Opening range breakout continuation.",
  },
];

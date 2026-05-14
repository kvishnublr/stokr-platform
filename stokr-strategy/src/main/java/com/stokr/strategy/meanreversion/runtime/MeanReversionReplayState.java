package com.stokr.strategy.meanreversion.runtime;

/**
 * Mutable per-run replay state for mean reversion (cooldown, virtual slots, sideways confirmation streak).
 * Thread-confined: one instance per backtest run.
 */
public final class MeanReversionReplayState {

    private int lastSignalBarIndex = -1;
    private final java.util.ArrayDeque<Integer> openEntryBars = new java.util.ArrayDeque<>();
    private int consecutiveSidewaysBars;

    public void beginBar(int barIndex, int maxHoldingCandles) {
        while (!openEntryBars.isEmpty() && barIndex - openEntryBars.peekFirst() >= maxHoldingCandles) {
            openEntryBars.pollFirst();
        }
    }

    public boolean canEmitSignal(int barIndex, MeanReversionRuntimeParams rp) {
        if (openEntryBars.size() >= rp.maxConcurrentPositions()) {
            return false;
        }
        if (rp.cooldownCandles() > 0 && lastSignalBarIndex >= 0
                && barIndex - lastSignalBarIndex < rp.cooldownCandles()) {
            return false;
        }
        return true;
    }

    public void recordSignalEmitted(int barIndex) {
        lastSignalBarIndex = barIndex;
        openEntryBars.addLast(barIndex);
    }

    /**
     * Compact resume snapshot (versioned prefix for future evolution).
     */
    public String toSnapshotLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("v1|").append(lastSignalBarIndex).append('|').append(consecutiveSidewaysBars).append('|');
        boolean first = true;
        for (Integer x : openEntryBars) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(x);
        }
        return sb.toString();
    }

    public void applySnapshotOrReset(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String payload = line.startsWith("v1|") ? line.substring(3) : line;
        String[] head = payload.split("\\|", 3);
        if (head.length < 2) {
            return;
        }
        lastSignalBarIndex = Integer.parseInt(head[0]);
        consecutiveSidewaysBars = Integer.parseInt(head[1]);
        openEntryBars.clear();
        if (head.length == 3 && !head[2].isBlank()) {
            for (String p : head[2].split(",")) {
                if (!p.isBlank()) {
                    openEntryBars.addLast(Integer.parseInt(p.trim()));
                }
            }
        }
    }

    public void trackSidewaysRegime(boolean sideways) {
        if (sideways) {
            consecutiveSidewaysBars++;
        } else {
            consecutiveSidewaysBars = 0;
        }
    }

    public boolean confirmationMet(int confirmationCandles) {
        if (confirmationCandles <= 0) {
            return true;
        }
        return consecutiveSidewaysBars >= confirmationCandles;
    }
}

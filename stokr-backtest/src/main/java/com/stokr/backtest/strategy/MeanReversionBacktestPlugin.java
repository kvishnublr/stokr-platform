package com.stokr.backtest.strategy;

import com.stokr.backtest.execution.BacktestEvaluationContext;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.meanreversion.MeanReversionParams;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import com.stokr.strategy.meanreversion.runtime.MeanReversionEvaluationEnvelope;
import com.stokr.strategy.meanreversion.runtime.MeanReversionReplayState;
import com.stokr.strategy.meanreversion.runtime.MeanReversionRuntimeParams;
import com.stokr.strategy.meanreversion.runtime.MeanReversionSessionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Objects;

@Component
@ConditionalOnBean(MeanReversionSignalGenerator.class)
@RequiredArgsConstructor
public class MeanReversionBacktestPlugin implements BacktestStrategyPlugin {

    private final MeanReversionSignalGenerator generator;
    private final MeanReversionSessionResolver sessionResolver;

    @Override
    public String strategyKey() {
        return StrategyKeys.MEAN_REVERSION_RANGE_FADE;
    }

    @Override
    public StrategySignalEntity evaluateAtOpen(
            BacktestEvaluationContext ctx,
            MarketdataCandle bar,
            int barIndex,
            String stepTimeframe
    ) {
        MeanReversionRuntimeParams rp = MeanReversionRuntimeParams.merge(
                ctx.execution().strategyParameters(),
                MeanReversionParams.V1
        );
        MeanReversionReplayState st = Objects.requireNonNullElseGet(
                ctx.meanReversionState(),
                MeanReversionReplayState::new
        );
        MeanReversionSessionResolver.SessionWindow sw = sessionResolver.resolve(rp.sessionFilter());
        ZoneId z;
        try {
            z = ZoneId.of(ctx.execution().timezone());
        } catch (Exception ex) {
            z = ZoneId.of("Asia/Kolkata");
        }
        MeanReversionEvaluationEnvelope env = new MeanReversionEvaluationEnvelope(
                rp,
                st,
                z,
                sw.start(),
                sw.end(),
                barIndex,
                ctx.execution().deterministicSeed(),
                ctx.execution().correlationId(),
                stepTimeframe,
                "1m".equalsIgnoreCase(stepTimeframe != null ? stepTimeframe : "1m") ? "5m" : stepTimeframe
        );
        return generator.evaluatePersistableAtOpen(
                ctx.execution().symbol(),
                ctx.execution().userId(),
                ctx.execution().runId(),
                ctx.pipeline(),
                bar.getOpenTime(),
                stepTimeframe,
                env
        );
    }
}

package com.stokr.backtest.service;

import com.stokr.backtest.domain.BacktestRun;
import com.stokr.backtest.repository.BacktestRunRepository;
import com.stokr.backtest.web.dto.BacktestJournalEntryDto;
import com.stokr.common.exception.ForbiddenException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.oms.journal.domain.EventStoreEntry;
import com.stokr.oms.journal.repository.EventStoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BacktestJournalQueryService {

    private final BacktestRunRepository runRepository;
    private final EventStoreRepository eventStoreRepository;

    @Transactional(readOnly = true)
    public List<BacktestJournalEntryDto> journalForRun(UUID runId, UUID userId) {
        BacktestRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("Run not found"));
        if (run.getUserId() == null || !run.getUserId().equals(userId)) {
            throw new ForbiddenException("Not your backtest run");
        }
        List<EventStoreEntry> rows = eventStoreRepository.findByBacktestRunOrdered(runId);
        return rows.stream().map(this::toDto).toList();
    }

    private BacktestJournalEntryDto toDto(EventStoreEntry e) {
        return new BacktestJournalEntryDto(
                e.getSequenceNum(),
                e.getEventType(),
                e.getPayloadJson(),
                e.getCreatedAt(),
                e.getChainHash(),
                e.getCorrelationId(),
                e.getStrategyKey()
        );
    }
}

package com.stokr.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BulkSymbolImportRequest(

        @NotNull
        @NotEmpty
        List<SymbolItem> symbols,

        /** When true, removes all existing symbols in the group before importing */
        Boolean replaceExisting

) {
    public record SymbolItem(
            String symbol,
            String tradingSymbol,
            String underlyingSymbol,
            String exchange,
            /** EQ | FUT | OPT | COM | CUR */
            String instrumentType,
            Long instrumentToken,
            Integer lotSize,
            BigDecimal tickSize,
            LocalDate expiry,
            BigDecimal strike,
            /** CE or PE */
            String optionType
    ) {}
}

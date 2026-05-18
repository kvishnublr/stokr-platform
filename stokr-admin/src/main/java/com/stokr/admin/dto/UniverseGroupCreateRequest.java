package com.stokr.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UniverseGroupCreateRequest(

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[A-Z0-9_]+$", message = "group_key must be UPPER_SNAKE_CASE")
        String groupKey,

        @NotBlank
        @Size(max = 200)
        String displayName,

        @Size(max = 500)
        String description,

        /** INDEX_CONSTITUENTS | CUSTOM | SECTOR | FUTURES | OPTIONS | COMMODITY */
        String universeType,

        String exchange,

        /** EQUITY | COMMODITY | FUTURES | OPTIONS | CURRENCY */
        String assetClass,

        /** NSE | NFO | MCX | CDS | BSE */
        String segment,

        /** EQ | FUT | OPT | COM | CUR */
        String instrumentType,

        /** When true, this group is eligible for automated symbol sync */
        Boolean autoManaged
) {}

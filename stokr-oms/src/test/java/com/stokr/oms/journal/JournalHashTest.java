package com.stokr.oms.journal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalHashTest {

    @Test
    void chainIsDeterministic() {
        String p1 = JournalHash.sha256Hex("{\"a\":1}");
        String c1 = JournalHash.chain(null, p1);
        String c2 = JournalHash.chain(null, p1);
        assertThat(c1).isEqualTo(c2);
        String c3 = JournalHash.chain(c1, JournalHash.sha256Hex("{\"b\":2}"));
        assertThat(c3).isNotBlank().hasSize(64);
    }
}

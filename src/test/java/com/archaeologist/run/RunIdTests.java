package com.archaeologist.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunIdTests {

    @Test
    void idIsTheStartTimeInUtcAndASuffix() {
        assertThat(RunId.of(Instant.parse("2026-11-05T22:18:14.987Z"), 0x3f9a).value())
                .isEqualTo("20261105T221814Z-3f9a");
    }

    @Test
    void suffixAlwaysHasFourDigits() {
        assertThat(RunId.of(Instant.parse("2026-01-02T03:04:05Z"), 0xa).value()).isEqualTo("20260102T030405Z-000a");
    }

    @Test
    void idsSortByStartTime() {
        String before = RunId.of(Instant.parse("2026-12-31T23:59:59Z"), 0xffff).value();
        String after = RunId.of(Instant.parse("2027-01-01T00:00:00Z"), 0).value();

        assertThat(before).isLessThan(after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../20261105T221814Z-3f9a", "20261105T221814Z-3f9a/reports",
            "20261105T221814Z-3F9A", "20261105T221814-3f9a", "latest"})
    void anythingElseIsNotARunId(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new RunId(value));
    }

    @Test
    void suffixMustFitInFourHexDigits() {
        assertThatIllegalArgumentException().isThrownBy(() -> RunId.of(Instant.EPOCH, RunId.SUFFIXES));
    }
}

package com.archaeologist.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunFolderTests {

    private final RunFolder run = new RunFolder(new RunId("20261105T221814Z-3f9a"),
            Path.of("archaeologist-output", "20261105T221814Z-3f9a"));

    @Test
    void resolvesPathsInsideTheFolder() {
        assertThat(run.resolve("reports/hotspots.md"))
                .isEqualTo(Path.of("archaeologist-output", "20261105T221814Z-3f9a", "reports", "hotspots.md"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../other-run/state.json", "reports/../../other-run", ".", ""})
    void refusesPathsOutsideTheFolder(String relative) {
        assertThatIllegalArgumentException().isThrownBy(() -> run.resolve(relative));
    }

    @Test
    void refusesAbsolutePaths(@TempDir Path elsewhere) {
        assertThatIllegalArgumentException().isThrownBy(() -> run.resolve(elsewhere.toString()));
    }
}

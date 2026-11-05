package com.archaeologist;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real startup path, as {@code main} runs it: configuration first, then the command line. */
class ArchaeologistApplicationStartupTests {

    private static final String YEARS = "archaeologist.history.years";

    private static final String TYPO = "archaeologist.histroy.years";

    /** picocli's switch for colors: off, so the assertions see plain text. */
    private static final String ANSI = "picocli.ansi";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureConsole() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, UTF_8));
        System.setErr(new PrintStream(err, true, UTF_8));
        System.setProperty(ANSI, "false");
    }

    @AfterEach
    void restoreConsole() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.clearProperty(YEARS);
        System.clearProperty(TYPO);
        System.clearProperty(ANSI);
    }

    @Test
    void validConfigurationReachesTheCommandLine() {
        assertThat(ArchaeologistApplication.run("--version")).isEqualTo(0);
        assertThat(out.toString(UTF_8)).contains("Code Archaeologist");
        assertThat(err.toString(UTF_8)).isEmpty();
    }

    @Test
    void invalidSettingStopsTheAgentWithOneLine() {
        System.setProperty(YEARS, "0");

        assertThat(ArchaeologistApplication.run("--version")).isEqualTo(4);
        // Nothing else: no Spring failure report on either stream
        assertThat(err.toString(UTF_8)).isEqualToIgnoringNewLines(
                "Error: Invalid configuration: archaeologist.history.years must be at least 1 (was 0)");
        assertThat(out.toString(UTF_8)).isEmpty();
    }

    @Test
    void unknownSettingStopsTheAgentWithOneLine() {
        System.setProperty(TYPO, "3");

        assertThat(ArchaeologistApplication.run("--version")).isEqualTo(4);
        assertThat(err.toString(UTF_8)).isEqualToIgnoringNewLines(
                "Error: Invalid configuration: archaeologist.histroy.years is not a known setting");
        assertThat(out.toString(UTF_8)).isEmpty();
    }
}

package com.archaeologist.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/** Unexpected errors, which the real commands cannot trigger on purpose. */
class ErrorReporterTests {

    @Command(name = "failing")
    static class FailingCommand implements Callable<Integer> {

        @Option(names = ArchaeologistCommand.STACKTRACE_OPTION)
        boolean stacktrace;

        @Override
        public Integer call() {
            throw new IllegalStateException("boom");
        }
    }

    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine commandLine = ArchaeologistCli.configure(new CommandLine(new FailingCommand()));
        commandLine.setErr(new PrintWriter(err, true));
        commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.OFF));
        return commandLine.execute(args);
    }

    @Test
    void unexpectedErrorIsShortWithAHint() {
        assertThat(run()).isEqualTo(ExitCode.FAILURE.code());
        assertThat(err.toString())
                .contains("Error: Unexpected error: IllegalStateException: boom")
                .contains("Run again with --stacktrace to see the details.")
                .doesNotContain("\tat ");
    }

    @Test
    void stacktraceOptionPrintsTheDetails() {
        assertThat(run("--stacktrace")).isEqualTo(ExitCode.FAILURE.code());
        assertThat(err.toString())
                .contains("Error: Unexpected error: IllegalStateException: boom")
                .contains("FailingCommand.call");
    }
}

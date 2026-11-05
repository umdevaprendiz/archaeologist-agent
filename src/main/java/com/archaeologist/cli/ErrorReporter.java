package com.archaeologist.cli;

import com.archaeologist.config.ConfigurationErrors;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * Turns errors into short messages on standard error and picks the exit code.
 *
 * <ul>
 *   <li>Invalid command line: the problem, suggestions for typos and a pointer to the help.</li>
 *   <li>Expected errors ({@link CliException}): the message only.</li>
 *   <li>Invalid configuration, found at startup: the message only.</li>
 *   <li>Unexpected errors: one line, plus the stack trace if {@code --stacktrace} was given.</li>
 * </ul>
 */
public final class ErrorReporter implements IParameterExceptionHandler, IExecutionExceptionHandler {

    @Override
    public int handleParseException(ParameterException ex, String[] args) {
        CommandLine command = ex.getCommandLine();
        PrintWriter err = command.getErr();
        err.println(error(command.getColorScheme(), ex.getMessage()));
        UnmatchedArgumentException.printSuggestions(ex, err);
        err.println("Run '" + command.getCommandSpec().qualifiedName() + " --help' for usage.");
        return ExitCode.USAGE.code();
    }

    @Override
    public int handleExecutionException(Exception ex, CommandLine command, ParseResult parseResult) {
        PrintWriter err = command.getErr();
        if (ex instanceof CliException expected) {
            err.println(error(command.getColorScheme(), expected.getMessage()));
            return expected.exitCode().code();
        }
        reportUnexpected(ex, "Unexpected error: " + describe(ex), stacktraceRequested(parseResult),
                err, command.getColorScheme());
        return ExitCode.FAILURE.code();
    }

    /**
     * Reports a failure while the application starts, before the command line can run: an invalid
     * setting or model catalog, or an unexpected error.
     *
     * @return the exit code
     */
    public static int reportStartupFailure(Throwable failure, String[] args, PrintWriter err) {
        return reportStartupFailure(failure, args, err, CommandLine.Help.defaultColorScheme(Ansi.AUTO));
    }

    static int reportStartupFailure(Throwable failure, String[] args, PrintWriter err, ColorScheme colors) {
        Optional<String> configurationError = ConfigurationErrors.find(failure);
        if (configurationError.isPresent()) {
            err.println(error(colors, configurationError.get()));
            return ExitCode.INVALID_CONFIGURATION.code();
        }
        // Spring's own messages name bean definition files by their absolute path: show the root cause only
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        boolean stacktrace = List.of(args).contains(ArchaeologistCommand.STACKTRACE_OPTION);
        reportUnexpected(failure, "Unexpected error at startup: " + describe(cause), stacktrace, err, colors);
        return ExitCode.FAILURE.code();
    }

    private static void reportUnexpected(Throwable ex, String message, boolean stacktrace, PrintWriter err,
            ColorScheme colors) {
        err.println(error(colors, message));
        if (stacktrace) {
            ex.printStackTrace(err);
        } else {
            err.println("Run again with " + ArchaeologistCommand.STACKTRACE_OPTION + " to see the details.");
        }
    }

    private static String error(ColorScheme colors, String message) {
        // errorText treats the message as plain text, so user input is never read as color markup
        return colors.errorText("Error: " + message).toString();
    }

    private static String describe(Throwable ex) {
        String type = ex.getClass().getSimpleName();
        return ex.getMessage() == null ? type : type + ": " + ex.getMessage();
    }

    /** The option is inherited, so it may appear before or after the subcommand. */
    private static boolean stacktraceRequested(ParseResult parseResult) {
        for (ParseResult level = parseResult; level != null; level = level.subcommand()) {
            if (level.hasMatchedOption(ArchaeologistCommand.STACKTRACE_OPTION)) {
                return true;
            }
        }
        return false;
    }
}

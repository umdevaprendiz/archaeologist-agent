package com.archaeologist.cli;

import java.io.PrintWriter;
import picocli.CommandLine;
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
 *   <li>Unexpected errors: one line, plus the stack trace if {@code --stacktrace} was given.</li>
 * </ul>
 */
final class ErrorReporter implements IParameterExceptionHandler, IExecutionExceptionHandler {

    @Override
    public int handleParseException(ParameterException ex, String[] args) {
        CommandLine command = ex.getCommandLine();
        PrintWriter err = command.getErr();
        err.println(error(command, ex.getMessage()));
        UnmatchedArgumentException.printSuggestions(ex, err);
        err.println("Run '" + command.getCommandSpec().qualifiedName() + " --help' for usage.");
        return ExitCode.USAGE.code();
    }

    @Override
    public int handleExecutionException(Exception ex, CommandLine command, ParseResult parseResult) {
        PrintWriter err = command.getErr();
        if (ex instanceof CliException expected) {
            err.println(error(command, expected.getMessage()));
            return expected.exitCode().code();
        }

        err.println(error(command, "Unexpected error: " + describe(ex)));
        if (stacktraceRequested(parseResult)) {
            ex.printStackTrace(err);
        } else {
            err.println("Run again with " + ArchaeologistCommand.STACKTRACE_OPTION + " to see the details.");
        }
        return ExitCode.FAILURE.code();
    }

    private static String error(CommandLine command, String message) {
        // errorText treats the message as plain text, so user input is never read as color markup
        return command.getColorScheme().errorText("Error: " + message).toString();
    }

    private static String describe(Exception ex) {
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

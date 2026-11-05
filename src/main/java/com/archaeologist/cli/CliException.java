package com.archaeologist.cli;

/**
 * An expected error: the command stops with a readable message and a specific exit code,
 * without a stack trace.
 */
public class CliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ExitCode exitCode;

    public CliException(ExitCode exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public ExitCode exitCode() {
        return exitCode;
    }

    /** For a command whose agents are not built yet; {@code plannedIn} names the features in docs/FEATURES.md. */
    static CliException notImplemented(String command, String plannedIn) {
        return new CliException(ExitCode.FAILURE,
                "The '" + command + "' command is not implemented yet (planned in " + plannedIn + ").");
    }
}

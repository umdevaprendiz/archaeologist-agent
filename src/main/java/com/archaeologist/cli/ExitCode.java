package com.archaeologist.cli;

/**
 * Exit codes of the {@code archaeologist} command. Scripts and CI pipelines rely on them, so an
 * existing code must never change meaning; new situations get new codes.
 */
public enum ExitCode {

    OK(0, "Success."),
    FAILURE(1, "The command failed (see the error message)."),
    USAGE(2, "Invalid command line: unknown command, or a missing or invalid argument."),
    INVALID_REPOSITORY(3, "The repository path does not exist, is not a folder, or is not a Git repository."),
    INVALID_CONFIGURATION(4, "Invalid configuration: a setting or the model catalog is missing, unknown or out of range.");

    private final int code;
    private final String description;

    ExitCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }
}

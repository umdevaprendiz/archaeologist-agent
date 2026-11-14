package com.archaeologist.git;

import java.util.Objects;

/**
 * Git is missing, or a Git command failed. The message is ready to show to the user, and never quotes
 * Git's own output, which may name absolute paths.
 */
public class GitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why Git failed, so callers can react, e.g. with the right exit code. */
    public enum Reason {
        /** Git is not installed, or not on the {@code PATH}. */
        NOT_INSTALLED,
        /** Git finds no repository in the folder: its {@code .git} is missing or damaged. */
        NOT_A_REPOSITORY,
        /** Git refuses to work in a folder that belongs to another user (its {@code safe.directory} check). */
        NOT_OWNED,
        /** The command took longer than its time limit. */
        TIMED_OUT,
        /** Any other failure. */
        FAILED
    }

    private final Reason reason;

    public GitException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public GitException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}

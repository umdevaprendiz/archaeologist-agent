package com.archaeologist.audit;

/**
 * The audit log can't be opened or written. The run must stop: its actions could no longer be traced.
 * The message is ready to show to the user.
 */
public class AuditLogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    AuditLogException(String message, Throwable cause) {
        super(message, cause);
    }
}

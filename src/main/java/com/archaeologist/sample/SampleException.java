package com.archaeologist.sample;

/** The sample repository can't be created. The message is ready to show to the user. */
public class SampleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SampleException(String message) {
        super(message);
    }

    SampleException(String message, Throwable cause) {
        super(message, cause);
    }
}

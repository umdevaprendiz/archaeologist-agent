package com.archaeologist.run;

/** The output folder of a run can't be created. The message is ready to show to the user. */
public class RunFolderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RunFolderException(String message, Throwable cause) {
        super(message, cause);
    }
}

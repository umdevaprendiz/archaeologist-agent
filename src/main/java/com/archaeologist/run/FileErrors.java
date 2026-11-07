package com.archaeologist.run;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/** Explains file system errors in messages about the files of a run. */
public final class FileErrors {

    private FileErrors() {
    }

    /** Why the file system refused. Never its file names: Java may have expanded them to absolute paths. */
    public static String reason(IOException ex) {
        if (ex instanceof AccessDeniedException) {
            return "permission denied";
        }
        if (ex instanceof FileAlreadyExistsException) {
            return "a file with that name is in the way";
        }
        if (ex instanceof NoSuchFileException) {
            return "no such file or folder";
        }
        if (ex instanceof FileSystemException fileSystem && fileSystem.getReason() != null) {
            return fileSystem.getReason();
        }
        // A plain IOException carries the operating system's message, e.g. "No space left on device".
        // Subclasses such as FileNotFoundException put the file name in theirs.
        if (ex.getClass() == IOException.class && ex.getMessage() != null) {
            return ex.getMessage();
        }
        return ex.getClass().getSimpleName();
    }
}

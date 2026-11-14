package com.archaeologist.excavator;

import java.util.Objects;

/**
 * How one commit changed one file, as {@code git log --numstat} counts it.
 *
 * @param path          the file's path after the commit, relative to the repository's root, with {@code /}
 *                      as the separator
 * @param previousPath  the file's path before the commit if the commit renamed or moved it, or {@code null}
 * @param linesAdded    lines added; 0 for a binary file
 * @param linesRemoved  lines removed; 0 for a binary file
 * @param binary        whether Git sees the file as binary, so its lines are not counted
 */
public record FileChange(String path, String previousPath, int linesAdded, int linesRemoved, boolean binary) {

    public FileChange {
        Objects.requireNonNull(path, "path");
        if (linesAdded < 0 || linesRemoved < 0) {
            throw new IllegalArgumentException("Line counts can't be negative");
        }
        if (binary && (linesAdded != 0 || linesRemoved != 0)) {
            throw new IllegalArgumentException("A binary file has no line counts");
        }
    }

    /** Whether the commit renamed or moved the file. */
    public boolean renamed() {
        return previousPath != null;
    }
}

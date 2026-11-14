package com.archaeologist.excavator;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A commit of the history, with what it changed.
 *
 * <p>Authors are counted, never listed: a commit keeps a number for its author, not a name or an e-mail
 * address, so no report or log can show who wrote what. The number comes after the repository's
 * {@code .mailmap}, so a person who changed name or e-mail keeps the same number.
 *
 * @param hash     the commit's full hash
 * @param authorId identifies the author within one {@link GitHistory}: commits by the same person have the
 *                 same number
 * @param date     when the author made the change
 * @param message  the whole message, subject and body, without trailing blank lines
 * @param files    the files it changed, in Git's order; empty for a commit that changed none
 */
public record Commit(String hash, int authorId, Instant date, String message, List<FileChange> files) {

    public Commit {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(message, "message");
        files = List.copyOf(files);
    }
}

package com.archaeologist.run;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Identifies one run, e.g. {@code 20261105T221814Z-3f9a}: the start time in UTC, so run folders sort by
 * date on every machine, then a random suffix, so two runs started in the same second still differ.
 *
 * <p>Only digits, lower-case letters and a dash: a valid folder name on Linux, Windows and macOS, and never
 * a path. A run id typed by a user can't point outside the output folder.
 *
 * @param value the id, as it appears in the folder name
 */
public record RunId(String value) {

    /** Number of distinct suffixes: four hex digits. */
    static final int SUFFIXES = 0x10000;

    private static final Pattern FORMAT = Pattern.compile("\\d{8}T\\d{6}Z-[0-9a-f]{4}");

    private static final DateTimeFormatter START = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    public RunId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a run id: " + value);
        }
    }

    /**
     * @param start  when the run starts
     * @param suffix a number from 0 to {@link #SUFFIXES} - 1
     */
    static RunId of(Instant start, int suffix) {
        if (suffix < 0 || suffix >= SUFFIXES) {
            throw new IllegalArgumentException("Suffix out of range: " + suffix);
        }
        return new RunId(START.format(start) + "-" + String.format("%04x", suffix));
    }

    @Override
    public String toString() {
        return value;
    }
}

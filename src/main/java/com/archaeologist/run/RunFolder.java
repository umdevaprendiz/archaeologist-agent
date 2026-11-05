package com.archaeologist.run;

import java.nio.file.Path;

/**
 * The output folder of one run. The run writes its files only inside it.
 *
 * @param id   the run's id, also the folder's name
 * @param path the folder, as configured: relative to the current folder unless the setting is absolute
 */
public record RunFolder(RunId id, Path path) {

    /**
     * A path inside this run's folder, e.g. {@code resolve("reports/hotspots.md")}. Names may come from the
     * repository under analysis (class names, for instance), so one that would leave the folder is refused.
     *
     * @throws IllegalArgumentException if {@code relative} is absolute, or points to the folder itself or outside it
     */
    public Path resolve(String relative) {
        Path folder = path.normalize();
        Path resolved = folder.resolve(relative).normalize();
        if (!resolved.startsWith(folder) || resolved.equals(folder)) {
            throw new IllegalArgumentException("Not a path inside the run folder: " + relative);
        }
        return resolved;
    }
}

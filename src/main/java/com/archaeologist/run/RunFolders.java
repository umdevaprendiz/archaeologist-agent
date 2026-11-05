package com.archaeologist.run;

import com.archaeologist.config.ArchaeologistProperties;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Creates the folder of each run: {@code <archaeologist.output.folder>/<run id>/}.
 *
 * <p>The output folder often ends up inside the repository under analysis: it is relative to the current
 * folder, and users run the agent from the repository. So when the agent creates the output folder, it puts
 * in it a {@code .gitignore} that ignores everything, itself included. The repository's {@code git status}
 * stays clean, and reports, which quote its code, are never committed by accident. An output folder that
 * already exists is left as it is: it may be the user's own.
 *
 * <p>Error messages show paths as configured, never expanded to absolute paths.
 */
@Component
public class RunFolders {

    static final String GITIGNORE = """
            # Created by the Code Archaeologist. Its runs are local output: never commit them.
            *
            """;

    /** A clash needs two runs in the same second with the same random suffix: retrying a few times is plenty. */
    static final int MAX_ATTEMPTS = 10;

    private final Path root;
    private final Supplier<RunId> ids;

    @Autowired
    public RunFolders(ArchaeologistProperties properties) {
        this(properties.output().folder(), RunFolders::newId);
    }

    RunFolders(Path root, Supplier<RunId> ids) {
        this.root = root;
        this.ids = ids;
    }

    private static RunId newId() {
        return RunId.of(Instant.now(), ThreadLocalRandom.current().nextInt(RunId.SUFFIXES));
    }

    /**
     * Creates the folder of a new run, and the output folder first if needed.
     *
     * @throws RunFolderException if a folder can't be created
     */
    public RunFolder create() {
        createRoot();
        for (int attempt = 1; ; attempt++) {
            RunId id = ids.get();
            Path folder = root.resolve(id.value());
            try {
                // Fails if the folder exists, so two runs never share one
                Files.createDirectory(folder);
                return new RunFolder(id, folder);
            } catch (FileAlreadyExistsException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new RunFolderException("Cannot create a run folder in " + root
                            + ": the last " + MAX_ATTEMPTS + " run ids tried were all taken", ex);
                }
            } catch (IOException ex) {
                throw failure("the run folder " + folder, ex);
            }
        }
    }

    private void createRoot() {
        if (Files.isDirectory(root)) {
            return;
        }
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(".gitignore"), GITIGNORE, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ex) {
            // Either a run that started at the same time wrote the .gitignore, or a file is in the way
            if (!Files.isDirectory(root)) {
                throw failure("the output folder " + root, ex);
            }
        } catch (IOException ex) {
            throw failure("the output folder " + root, ex);
        }
    }

    private static RunFolderException failure(String what, IOException ex) {
        return new RunFolderException("Cannot create " + what + ": " + reason(ex), ex);
    }

    /** Why the file system refused. Never its file names: Java may have expanded them to absolute paths. */
    private static String reason(IOException ex) {
        if (ex instanceof AccessDeniedException) {
            return "permission denied";
        }
        if (ex instanceof FileAlreadyExistsException) {
            return "a file with that name is in the way";
        }
        if (ex instanceof FileSystemException fileSystem && fileSystem.getReason() != null) {
            return fileSystem.getReason();
        }
        return ex.getClass().getSimpleName();
    }
}

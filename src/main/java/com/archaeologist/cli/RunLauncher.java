package com.archaeologist.cli;

import com.archaeologist.audit.Agent;
import com.archaeologist.audit.AuditLog;
import com.archaeologist.audit.AuditLogException;
import com.archaeologist.audit.AuditLogs;
import com.archaeologist.config.ArchaeologistProperties;
import com.archaeologist.run.RunFolder;
import com.archaeologist.run.RunFolderException;
import com.archaeologist.run.RunFolders;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Component;

/**
 * Runs the work of a command as a run: creates the run's folder, opens its audit log, tells the user
 * where the output is, and records in the audit log when the run starts and how it ends. A run that is
 * killed has a {@code run-started} event and no {@code run-finished}.
 */
@Component
public class RunLauncher {

    static final String RUN_STARTED = "run-started";
    static final String RUN_FINISHED = "run-finished";

    private final RunFolders folders;
    private final AuditLogs auditLogs;
    private final ArchaeologistProperties properties;
    private final VersionProvider version;

    public RunLauncher(RunFolders folders, AuditLogs auditLogs, ArchaeologistProperties properties,
            VersionProvider version) {
        this.folders = folders;
        this.auditLogs = auditLogs;
        this.properties = properties;
        this.version = version;
    }

    /**
     * @param command    the command's name
     * @param repository the repository under analysis, already checked
     * @param out        where to tell the user about the run
     * @param work       the command's work, which returns the exit code
     * @return the exit code returned by {@code work}
     * @throws CliException if the run can't start or its audit log can't be written, or as thrown by {@code work}
     */
    int launch(String command, Path repository, PrintWriter out, ToIntFunction<StartedRun> work) {
        try {
            RunFolder folder = folders.create();
            try (AuditLog audit = auditLogs.open(folder, repository)) {
                out.println("Run " + folder.id() + ": output in " + folder.path());
                audit.record(Agent.ORCHESTRATOR, RUN_STARTED, describeStart(command, repository),
                        "run " + folder.id() + " started");
                return runAndRecordEnd(audit, () -> work.applyAsInt(new StartedRun(folder, audit)));
            }
        } catch (RunFolderException | AuditLogException ex) {
            throw new CliException(ExitCode.FAILURE, ex.getMessage());
        }
    }

    private static int runAndRecordEnd(AuditLog audit, IntSupplier work) {
        int exitCode;
        try {
            exitCode = work.getAsInt();
        } catch (CliException ex) {
            recordFailure(audit, ex, failed(ex.exitCode().code()) + ": " + ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            recordFailure(audit, ex, "failed with an unexpected error: " + describe(ex));
            throw ex;
        }
        audit.record(Agent.ORCHESTRATOR, RUN_FINISHED, "",
                exitCode == ExitCode.OK.code() ? "succeeded" : failed(exitCode));
        return exitCode;
    }

    /** The original failure is the one reported: a failure to record it only goes along with it. */
    private static void recordFailure(AuditLog audit, RuntimeException failure, String outcome) {
        try {
            audit.record(Agent.ORCHESTRATOR, RUN_FINISHED, "", outcome);
        } catch (RuntimeException ex) {
            failure.addSuppressed(ex);
        }
    }

    private String describeStart(String command, Path repository) {
        return command + " on repository '" + name(repository) + "'; " + version.summary()
                + "; settings: " + properties.decisionSummary();
    }

    /** Only the folder's name: the rest of the path shows where the user keeps it. */
    private static String name(Path repository) {
        Path name = repository.toAbsolutePath().normalize().getFileName();
        return name != null ? name.toString() : repository.toString();
    }

    private static String failed(int exitCode) {
        return "failed (exit code " + exitCode + ")";
    }

    private static String describe(Throwable ex) {
        String type = ex.getClass().getSimpleName();
        return ex.getMessage() == null ? type : type + ": " + ex.getMessage();
    }
}

package com.archaeologist.audit;

import com.archaeologist.run.RunFolder;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Opens the audit log of each run. */
@Component
public class AuditLogs {

    /** The audit log's file in the run folder. */
    public static final String FILE_NAME = "audit.jsonl";

    private final Clock clock;

    @Autowired
    public AuditLogs() {
        this(Clock.systemUTC());
    }

    AuditLogs(Clock clock) {
        this.clock = clock;
    }

    /**
     * Opens the audit log of a run, and creates it if needed.
     *
     * @param run        the run
     * @param repository the repository under analysis: its absolute path becomes {@code <repo>} in the log
     * @throws AuditLogException if the file can't be opened
     */
    public AuditLog open(RunFolder run, Path repository) {
        return AuditLog.open(run.resolve(FILE_NAME), clock, PathRedactor.forRun(repository));
    }
}

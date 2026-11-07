package com.archaeologist.cli;

import com.archaeologist.audit.AuditLog;
import com.archaeologist.run.RunFolder;

/**
 * A run in progress, handed to a command's work by {@link RunLauncher}.
 *
 * @param folder where the run writes its files
 * @param audit  where the agents record their actions and decisions
 */
public record StartedRun(RunFolder folder, AuditLog audit) {
}

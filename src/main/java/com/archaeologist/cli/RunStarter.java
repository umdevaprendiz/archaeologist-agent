package com.archaeologist.cli;

import com.archaeologist.run.RunFolder;
import com.archaeologist.run.RunFolderException;
import com.archaeologist.run.RunFolders;
import java.io.PrintWriter;

/** Starts a run for a command: creates the run's folder and tells the user where it is. */
final class RunStarter {

    private RunStarter() {
    }

    static RunFolder start(RunFolders folders, PrintWriter out) {
        RunFolder run;
        try {
            run = folders.create();
        } catch (RunFolderException ex) {
            throw new CliException(ExitCode.FAILURE, ex.getMessage());
        }
        out.println("Run " + run.id() + ": output in " + run.path());
        return run;
    }
}

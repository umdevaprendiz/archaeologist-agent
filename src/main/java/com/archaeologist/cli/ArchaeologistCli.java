package com.archaeologist.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Entry point of the command line: builds the command tree with Spring-managed commands, readable
 * error handling, and the list of exit codes in every help screen.
 */
@Component
public class ArchaeologistCli {

    private final IFactory factory;

    public ArchaeologistCli(IFactory factory) {
        this.factory = factory;
    }

    /** Runs the command line and returns the process exit code. */
    public int execute(String... args) {
        return newCommandLine().execute(args);
    }

    /** A fresh, fully configured command line. Use one per execution: commands keep the parsed arguments. */
    public CommandLine newCommandLine() {
        return configure(new CommandLine(ArchaeologistCommand.class, factory));
    }

    /** Applies the shared settings to a command and all its subcommands. */
    static CommandLine configure(CommandLine commandLine) {
        ErrorReporter errors = new ErrorReporter();
        commandLine.setParameterExceptionHandler(errors);
        commandLine.setExecutionExceptionHandler(errors);
        commandLine.setUsageHelpAutoWidth(true);
        addExitCodeList(commandLine, exitCodeList());
        return commandLine;
    }

    private static Map<String, String> exitCodeList() {
        Map<String, String> list = new LinkedHashMap<>();
        for (ExitCode exitCode : ExitCode.values()) {
            list.put(String.valueOf(exitCode.code()), exitCode.description());
        }
        return list;
    }

    private static void addExitCodeList(CommandLine commandLine, Map<String, String> list) {
        commandLine.getCommandSpec().usageMessage()
                .exitCodeListHeading("%nExit codes:%n")
                .exitCodeList(list);
        commandLine.getSubcommands().values().forEach(subcommand -> addExitCodeList(subcommand, list));
    }
}

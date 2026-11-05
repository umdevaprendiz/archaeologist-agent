/**
 * <b>Command line</b>: the {@code archaeologist} command and its subcommands ({@code hotspots},
 * {@code run}, {@code scout}), built with picocli.
 *
 * <p>Commands are Spring beans, so they can call the agents. Errors become short messages on
 * standard error and documented exit codes ({@link com.archaeologist.cli.ExitCode}); stack traces
 * only appear for unexpected errors, with {@code --stacktrace}.
 */
package com.archaeologist.cli;

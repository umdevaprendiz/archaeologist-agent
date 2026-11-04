/**
 * <b>Orchestrator</b>: the graph that connects the agents.
 *
 * <pre>
 * Scout -> Excavator -> Cartographer -> Prioritization -> [target queue]
 *   for each target:
 *     Guardian (tests on the old code) --failed--> discard target
 *     Restorer -> Guardian (same tests on the new code) -> Judge
 *       Judge: green                       -> Chronicler (commit + report)
 *       Judge: failed, attempt &lt; limit   -> back to Restorer
 *       Judge: failed, limit reached       -> discard branch + record why
 * </pre>
 *
 * <p>Keeps the state (targets, tests, results, attempts) in checkpoints so an interrupted run
 * can be resumed.
 */
package com.archaeologist.orchestrator;

/**
 * <b>Prioritization</b>: crosses Excavator and Cartographer data and picks the next targets.
 *
 * <p>Multiplicative risk score (changes often <i>and</i> is complex):
 * <pre>
 * risk = churn * complexity * (1 + w_fixes * fixes + w_authors * authors)
 * </pre>
 * with each signal normalized from 0 to 1 within the repository. The final queue also weighs
 * the target's feasibility (does it fit in the model's context? how many external dependencies
 * does it have?).
 *
 * <p>Uses LLM: no.
 */
package com.archaeologist.prioritization;

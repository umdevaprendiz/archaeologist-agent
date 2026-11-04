/**
 * <b>Judge</b>: approves the branch, sends it back for fixes, or discards it.
 *
 * <p>Decides with objective rules only: it compiled, all characterization tests passed, the
 * test files were not changed (same hash as the original), coverage did not drop and the
 * mutation score is above the configured minimum.
 *
 * <p>Uses LLM: no.
 */
package com.archaeologist.judge;

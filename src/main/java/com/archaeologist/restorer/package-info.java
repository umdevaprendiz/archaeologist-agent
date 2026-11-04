/**
 * <b>Restorer</b>: modernizes the code.
 *
 * <ul>
 *   <li>Version migration with OpenRewrite, on the whole repository, in a base branch
 *       ({@code archaeologist/migrate-java21}).</li>
 *   <li>Idiomatic refactorings with the LLM (Streams, switch expressions, pattern matching,
 *       text blocks), one per branch, stacked on the base branch.</li>
 * </ul>
 *
 * <p>Never edits the characterization tests. Refactorings that change the public API
 * (e.g. POJO to record) must update all callers together.
 *
 * <p>Uses LLM: yes.
 */
package com.archaeologist.restorer;

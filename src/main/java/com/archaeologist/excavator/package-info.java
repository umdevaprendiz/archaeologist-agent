/**
 * <b>Excavator</b>: mines the Git history (default: last 5 years).
 *
 * <p>Measures, per file: churn (commits and changed lines), bug-fix commits, distinct authors
 * and temporal coupling (files that always change together). Follows renames and ignores huge
 * commits (reformatting, license header changes), which inflate churn.
 *
 * <p>Uses LLM: optional, only to classify commits with ambiguous messages.
 */
package com.archaeologist.excavator;

/**
 * <b>Chronicler</b>: records the work.
 *
 * <p>Commits to {@code archaeologist/*} branches (never to the main branch) and writes the
 * per-target and overall reports. The audit log of every action and decision is not kept here: every
 * agent writes to it (package {@code com.archaeologist.audit}).
 *
 * <p>Uses LLM: only to summarize.
 */
package com.archaeologist.chronicler;

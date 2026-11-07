package com.archaeologist.audit;

import java.util.Locale;

/** Who records an event in the audit log: one of the agents, or the orchestrator that runs them. */
public enum Agent {

    SCOUT,
    EXCAVATOR,
    CARTOGRAPHER,
    PRIORITIZATION,
    GUARDIAN,
    RESTORER,
    JUDGE,
    CHRONICLER,
    /** The run itself: when it starts and how it ends. */
    ORCHESTRATOR;

    /** The name written in the log, in lower case, e.g. {@code excavator}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}

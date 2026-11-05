package com.archaeologist.config;

/**
 * How capable a machine is of running local models, from its usable memory. Decides which models
 * the Scout tries (see {@link ModelCatalog#profiles()}).
 */
public enum MachineProfile {
    WEAK,
    MEDIUM,
    STRONG
}

/**
 * <b>Configuration</b>: the agent's typed settings ({@link com.archaeologist.config.ArchaeologistProperties},
 * the {@code archaeologist.*} properties) and the model catalog
 * ({@link com.archaeologist.config.ModelCatalog}, from {@code config/models.yaml}).
 *
 * <p>Both are loaded and checked at startup. An invalid value stops the agent before it does any
 * work, with one readable line per problem ({@link com.archaeologist.config.ConfigurationErrors}).
 */
package com.archaeologist.config;

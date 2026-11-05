package com.archaeologist.config;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.config.CustomEditorConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the settings and loads the model catalog at startup, so an invalid one stops the agent early. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArchaeologistProperties.class)
class SettingsConfiguration {

    /** Path settings are plain file system paths, as written (see {@link PlainPathEditor}). */
    @Bean
    static CustomEditorConfigurer plainPathEditor() {
        CustomEditorConfigurer configurer = new CustomEditorConfigurer();
        configurer.setCustomEditors(Map.of(Path.class, PlainPathEditor.class));
        return configurer;
    }

    /** Unknown settings in system properties and environment variables are rejected too. */
    @Bean
    static UnknownSettingsAdvisor unknownSettingsAdvisor() {
        return new UnknownSettingsAdvisor();
    }

    @Bean
    ModelCatalog modelCatalog(ModelCatalogLoader loader, ArchaeologistProperties properties) {
        return loader.load(properties.modelCatalog());
    }
}

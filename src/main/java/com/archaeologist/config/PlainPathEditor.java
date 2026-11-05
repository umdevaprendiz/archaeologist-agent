package com.archaeologist.config;

import java.beans.PropertyEditorSupport;
import java.nio.file.Path;

/**
 * Reads path settings as plain file system paths, exactly as written. Spring's default editor for
 * {@link Path} looks the text up on the class path first, so {@code models.yaml} would silently become
 * the absolute path of the bundled catalog. Registered in {@link SettingsConfiguration}; a property
 * editor, because Spring Boot tries editors before {@code @ConfigurationPropertiesBinding} converters.
 */
class PlainPathEditor extends PropertyEditorSupport {

    @Override
    public void setAsText(String text) {
        setValue(text == null || text.isBlank() ? null : Path.of(text));
    }
}

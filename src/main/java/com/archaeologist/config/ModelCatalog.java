package com.archaeologist.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The catalog of local models, from {@code config/models.yaml}. Loaded by {@link ModelCatalogLoader}.
 *
 * <p>Numbers are boxed so that a missing value is reported instead of silently read as 0.
 *
 * @param fixedModel  the model every agent uses until the Scout picks one (phase 6)
 * @param profiles    the candidate models of each machine profile; all three profiles are required
 * @param calibration settings of the Scout's calibration
 */
public record ModelCatalog(
        @NotBlank(message = "must not be blank") String fixedModel,
        @NotNull(message = "is missing") Map<MachineProfile, @Valid ProfileModels> profiles,
        @NotNull(message = "is missing") @Valid Calibration calibration) {

    public ModelCatalog {
        // Null is reported by the validation, which runs after the constructor
        if (profiles != null) {
            Set<MachineProfile> missing = EnumSet.allOf(MachineProfile.class);
            missing.removeAll(profiles.keySet());
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "profiles must define weak, medium and strong (missing: " + names(missing) + ")");
            }
            profiles = Collections.unmodifiableMap(new EnumMap<>(profiles));
        }
    }

    private static String names(Set<MachineProfile> profiles) {
        return profiles.stream().map(profile -> profile.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }

    /**
     * The models of one machine profile.
     *
     * @param minMemoryGb usable memory, in GB, a machine needs for this profile
     * @param candidates  models to try, the preferred one first
     */
    public record ProfileModels(
            @NotNull(message = "is missing") @PositiveOrZero(message = "must be 0 or more") Double minMemoryGb,
            @NotEmpty(message = "must list at least one model")
                    List<@NotBlank(message = "must not be blank") String> candidates) {

        public ProfileModels {
            candidates = candidates == null ? null : Collections.unmodifiableList(new ArrayList<>(candidates));
        }
    }

    /**
     * How the Scout checks that a model works on this machine.
     *
     * @param minTokensPerSecond below this speed, the Scout steps down one profile
     * @param task               sample refactoring the model must get right
     * @param systemReserveGb    memory, in GB, left for the operating system
     * @param contextReserveGb   memory, in GB, left for the model's context window
     */
    public record Calibration(
            @NotNull(message = "is missing") @Positive(message = "must be greater than 0") Double minTokensPerSecond,
            @NotBlank(message = "must not be blank") String task,
            @NotNull(message = "is missing") @PositiveOrZero(message = "must be 0 or more") Double systemReserveGb,
            @NotNull(message = "is missing") @PositiveOrZero(message = "must be 0 or more") Double contextReserveGb) {
    }
}

package com.archaeologist.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The agent's settings: the {@code archaeologist.*} properties. The defaults, with a comment for each
 * one, are in {@code application.yml}. Unknown settings are rejected wherever they are set (YAML, system
 * properties or environment variables, see {@link UnknownSettingsAdvisor}), so a typo never goes unnoticed.
 *
 * <p>Every constraint spells out its message, so errors are in English whatever the machine's locale.
 *
 * @param history      how much Git history the Excavator reads
 * @param riskWeights  weights of the risk score
 * @param retry        how many times a target is attempted
 * @param quality      minimum quality of the characterization tests
 * @param output       where runs write their files
 * @param modelCatalog model catalog file; {@code null} means the catalog bundled with the agent
 */
@Validated
@ConfigurationProperties(prefix = "archaeologist", ignoreUnknownFields = false)
public record ArchaeologistProperties(
        @NotNull(message = "is missing") @Valid History history,
        @NotNull(message = "is missing") @Valid RiskWeights riskWeights,
        @NotNull(message = "is missing") @Valid Retry retry,
        @NotNull(message = "is missing") @Valid Quality quality,
        @NotNull(message = "is missing") @Valid Output output,
        Path modelCatalog) {

    private static final String PERCENT = "must be between 0 and 100";

    /**
     * The settings that steer the agents' decisions, on one line for the audit log, e.g.
     * {@code history.years=5, risk-weights.fixes=1.0, ...}. Path settings are left out: they can show the
     * user's folders. A new setting that changes a decision belongs here.
     */
    public String decisionSummary() {
        return "history.years=" + history.years()
                + ", risk-weights.fixes=" + riskWeights.fixes()
                + ", risk-weights.authors=" + riskWeights.authors()
                + ", retry.max-attempts=" + retry.maxAttempts()
                + ", quality.min-line-coverage-percent=" + quality.minLineCoveragePercent()
                + ", quality.min-mutation-score-percent=" + quality.minMutationScorePercent();
    }

    /** @param years how many years back the Excavator reads the Git history */
    public record History(@Min(value = 1, message = "must be at least 1") int years) {
    }

    /**
     * Weights of the risk score: {@code churn × complexity × (1 + fixes × fix commits + authors × authors)},
     * with every signal normalized from 0 to 1 within the repository.
     *
     * @param fixes   weight of the bug-fix commits
     * @param authors weight of the number of distinct authors
     */
    public record RiskWeights(
            @PositiveOrZero(message = "must be 0 or more") double fixes,
            @PositiveOrZero(message = "must be 0 or more") double authors) {
    }

    /** @param maxAttempts attempts per target, the first one included, before its branch is discarded */
    public record Retry(@Min(value = 1, message = "must be at least 1") int maxAttempts) {
    }

    /**
     * Minimum quality of the characterization tests, measured on the target class.
     *
     * @param minLineCoveragePercent   minimum line coverage, in percent
     * @param minMutationScorePercent  minimum mutation score, in percent
     */
    public record Quality(
            @Min(value = 0, message = PERCENT) @Max(value = 100, message = PERCENT) int minLineCoveragePercent,
            @Min(value = 0, message = PERCENT) @Max(value = 100, message = PERCENT) int minMutationScorePercent) {
    }

    /** @param folder each run writes its reports, state and logs to its own subfolder of this folder */
    public record Output(@NotNull(message = "is missing") Path folder) {
    }
}

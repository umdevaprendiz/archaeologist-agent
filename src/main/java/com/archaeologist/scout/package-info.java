/**
 * <b>Scout</b>: detects the hardware and picks the local model.
 *
 * <p>Collects CPU, RAM, GPU/VRAM and disk; computes the memory budget; picks the largest
 * model from {@code config/models.yaml} that fits; downloads and calibrates it (tokens per
 * second plus a sample refactoring that must compile). Saves the result to
 * {@code machine-profile.json} and downgrades the model on its own if memory runs out.
 *
 * <p>Uses LLM: only for calibration.
 */
package com.archaeologist.scout;

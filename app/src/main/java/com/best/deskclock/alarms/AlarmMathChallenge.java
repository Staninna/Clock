// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * A problem that must be solved to snooze or dismiss an alarm; see {@link MathChallengeLevels}.
 *
 * @param expression the human readable expression, e.g. {@code "14 × 7 + 38"}
 * @param answer     the expected result of the expression
 */
public record AlarmMathChallenge(@NonNull String expression, int answer) {

    public boolean matches(@Nullable String input) {
        final String normalizedInput = input == null ? "" : input.trim();

        try {
            return Integer.parseInt(normalizedInput) == answer;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @NonNull
    public static AlarmMathChallenge create(@NonNull String hardness, @NonNull Random random) {
        return MathChallengeLevels.create(hardness, random);
    }
}

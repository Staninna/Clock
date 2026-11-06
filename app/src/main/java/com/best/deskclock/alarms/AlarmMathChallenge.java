// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_EASY;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_HARD;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_NORMAL;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * A single arithmetic problem that must be solved to snooze or dismiss an alarm.
 *
 * <p>Problems mix addition, subtraction and multiplication depending on the hardness level.
 * Answers are always non-negative integers, so they can be entered with a plain digit keypad.</p>
 *
 * @param expression the human readable expression, e.g. {@code "14 × 7 + 38"}
 * @param answer     the expected result of the expression
 */
public record AlarmMathChallenge(@NonNull String expression, int answer) {

    private static final String PLUS = " + ";
    private static final String MINUS = " − ";
    private static final String TIMES = " × ";

    public boolean matches(@Nullable String input) {
        final String normalizedInput = input == null ? "" : input.trim();

        try {
            return Integer.parseInt(normalizedInput) == answer;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * @return how many problems must be solved in a row for the given hardness level.
     */
    public static int requiredProblemCount(@NonNull String hardness) {
        return switch (hardness) {
            case MATH_HARDNESS_LEVEL_NORMAL -> 2;
            case MATH_HARDNESS_LEVEL_HARD -> 3;
            default -> 1;
        };
    }

    @NonNull
    public static AlarmMathChallenge create(@NonNull String hardness, @NonNull Random random) {
        return switch (hardness) {
            case MATH_HARDNESS_LEVEL_NORMAL -> createNormal(random);
            case MATH_HARDNESS_LEVEL_HARD -> createHard(random);
            case MATH_HARDNESS_LEVEL_EASY -> createEasy(random);
            default -> createEasy(random);
        };
    }

    /**
     * Two-digit addition/subtraction or the multiplication table.
     */
    @NonNull
    private static AlarmMathChallenge createEasy(@NonNull Random random) {
        switch (random.nextInt(3)) {
            case 0 -> {
                final int a = between(random, 12, 99);
                final int b = between(random, 12, 99);
                return new AlarmMathChallenge(a + PLUS + b, a + b);
            }
            case 1 -> {
                final int a = between(random, 30, 99);
                final int b = between(random, 11, a - 1);
                return new AlarmMathChallenge(a + MINUS + b, a - b);
            }
            default -> {
                final int a = between(random, 3, 9);
                final int b = between(random, 3, 9);
                return new AlarmMathChallenge(a + TIMES + b, a * b);
            }
        }
    }

    /**
     * Mixed operations: {@code a × b ± c}, three-digit sums, or teen × teen.
     */
    @NonNull
    private static AlarmMathChallenge createNormal(@NonNull Random random) {
        switch (random.nextInt(4)) {
            case 0 -> {
                final int a = between(random, 3, 9);
                final int b = between(random, 11, 29);
                final int c = between(random, 11, 99);
                return new AlarmMathChallenge(a + TIMES + b + PLUS + c, a * b + c);
            }
            case 1 -> {
                final int a = between(random, 3, 9);
                final int b = between(random, 11, 29);
                final int product = a * b;
                final int c = between(random, 11, Math.min(99, product - 1));
                return new AlarmMathChallenge(a + TIMES + b + MINUS + c, product - c);
            }
            case 2 -> {
                final int a = between(random, 100, 499);
                final int b = between(random, 100, 499);
                final int c = between(random, 100, a + b - 1);
                return new AlarmMathChallenge(a + PLUS + b + MINUS + c, a + b - c);
            }
            default -> {
                final int a = between(random, 11, 19);
                final int b = between(random, 11, 19);
                return new AlarmMathChallenge(a + TIMES + b, a * b);
            }
        }
    }

    /**
     * Two-digit multiplication and multi-step expressions.
     */
    @NonNull
    private static AlarmMathChallenge createHard(@NonNull Random random) {
        switch (random.nextInt(4)) {
            case 0 -> {
                final int a = between(random, 12, 39);
                final int b = between(random, 12, 39);
                return new AlarmMathChallenge(a + TIMES + b, a * b);
            }
            case 1 -> {
                final int a = between(random, 6, 19);
                final int b = between(random, 3, 9);
                final int c = between(random, 6, 19);
                final int d = between(random, 3, 9);
                return new AlarmMathChallenge(a + TIMES + b + PLUS + c + TIMES + d, a * b + c * d);
            }
            case 2 -> {
                final int a = between(random, 11, 49);
                final int b = between(random, 11, 49);
                final int c = between(random, 3, 9);
                return new AlarmMathChallenge("(" + a + PLUS + b + ")" + TIMES + c, (a + b) * c);
            }
            default -> {
                final int a = between(random, 12, 29);
                final int b = between(random, 4, 9);
                final int product = a * b;
                final int c = between(random, 11, product - 1);
                return new AlarmMathChallenge(a + TIMES + b + MINUS + c, product - c);
            }
        }
    }

    /**
     * @return a random integer in the inclusive range {@code [min, max]}.
     */
    private static int between(@NonNull Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}

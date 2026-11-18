// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_EASY;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_FAIR;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_HARD;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_MODERATE;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_NORMAL;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_TOUGH;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_TRICKY;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.List;
import java.util.Random;

/**
 * The math challenge hardness levels: the kinds of problems each level asks, and how many must be
 * solved in a row.
 *
 * <p>Every problem has a non-negative integer answer, so it can be typed on a plain digit keypad.
 * Each level picks one of its problem shapes at random, then draws the shape's numbers.</p>
 */
public final class MathChallengeLevels {

    private static final String PLUS = " + ";
    private static final String MINUS = " − ";
    private static final String TIMES = " × ";

    /** A kind of problem, with the ranges its numbers are drawn from. */
    @FunctionalInterface
    interface Shape {
        @NonNull
        AlarmMathChallenge create(@NonNull Random random);
    }

    /**
     * @param problemCount how many problems must be solved in a row
     */
    @VisibleForTesting
    record Level(@NonNull String key, int problemCount, @NonNull List<Shape> shapes) {
    }

    @VisibleForTesting
    static final List<Level> LEVELS = List.of(
        // Two-digit addition/subtraction or the multiplication table.
        new Level(MATH_HARDNESS_LEVEL_EASY, 1, List.of(
            sum(12, 99, 12, 99), difference(30, 99, 11), product(3, 9, 3, 9))),
        // Two-digit sums and differences that carry, or a single digit times a teen.
        new Level(MATH_HARDNESS_LEVEL_FAIR, 1, List.of(
            sum(25, 99, 25, 99), difference(50, 99, 15), product(3, 9, 11, 15))),
        // Two-step sums, the full times table up to 12 × 12, or a small a × b + c.
        new Level(MATH_HARDNESS_LEVEL_MODERATE, 2, List.of(
            sumMinus(20, 99, 20, 99, 10), product(6, 12, 6, 12), productPlus(2, 5, 11, 19, 10, 49),
            sum(100, 499, 25, 99))),
        // Mixed operations: a × b ± c, three-digit sums, or teen × teen.
        new Level(MATH_HARDNESS_LEVEL_NORMAL, 2, List.of(
            productPlus(3, 9, 11, 29, 11, 99), productMinus(3, 9, 11, 29, 11, 99),
            sumMinus(100, 499, 100, 499, 100), product(11, 19, 11, 19))),
        // Larger a × b ± c, teen × teen up to 25, or three-digit a + b − c.
        new Level(MATH_HARDNESS_LEVEL_TRICKY, 2, List.of(
            productPlus(4, 9, 21, 49, 11, 99), productMinus(4, 9, 21, 49, 11, 199),
            product(13, 25, 13, 25), sumMinus(200, 699, 200, 699, 100))),
        // Like hard, with somewhat smaller numbers.
        new Level(MATH_HARDNESS_LEVEL_TOUGH, 3, List.of(
            product(12, 29, 12, 29), twoProducts(6, 15, 3, 7), sumTimes(11, 35, 3, 7),
            productMinus(12, 25, 4, 9, 11, Integer.MAX_VALUE))),
        // Two-digit multiplication and multi-step expressions.
        new Level(MATH_HARDNESS_LEVEL_HARD, 3, List.of(
            product(12, 39, 12, 39), twoProducts(6, 19, 3, 9), sumTimes(11, 49, 3, 9),
            productMinus(12, 29, 4, 9, 11, Integer.MAX_VALUE)))
    );

    private MathChallengeLevels() {
    }

    /**
     * @return how many problems must be solved in a row at the given hardness level.
     */
    public static int requiredProblemCount(@NonNull String hardness) {
        return level(hardness).problemCount();
    }

    /**
     * @return a random problem of the given hardness level.
     */
    @NonNull
    public static AlarmMathChallenge create(@NonNull String hardness, @NonNull Random random) {
        final List<Shape> shapes = level(hardness).shapes();
        return shapes.get(random.nextInt(shapes.size())).create(random);
    }

    /**
     * @return the level with the given key. A key that is no level (none, or one saved by another
     * version of the app) gets the easiest level: an alarm must stay possible to dismiss.
     */
    @NonNull
    private static Level level(@NonNull String hardness) {
        for (Level level : LEVELS) {
            if (level.key().equals(hardness)) {
                return level;
            }
        }
        return LEVELS.get(0);
    }

    /** a + b */
    @NonNull
    private static Shape sum(int aMin, int aMax, int bMin, int bMax) {
        return random -> {
            final int a = between(random, aMin, aMax);
            final int b = between(random, bMin, bMax);
            return new AlarmMathChallenge(a + PLUS + b, a + b);
        };
    }

    /** a − b, with b below a */
    @NonNull
    private static Shape difference(int aMin, int aMax, int bMin) {
        return random -> {
            final int a = between(random, aMin, aMax);
            final int b = between(random, bMin, a - 1);
            return new AlarmMathChallenge(a + MINUS + b, a - b);
        };
    }

    /** a × b */
    @NonNull
    private static Shape product(int aMin, int aMax, int bMin, int bMax) {
        return random -> {
            final int a = between(random, aMin, aMax);
            final int b = between(random, bMin, bMax);
            return new AlarmMathChallenge(a + TIMES + b, a * b);
        };
    }

    /** a + b − c, with c below a + b */
    @NonNull
    private static Shape sumMinus(int aMin, int aMax, int bMin, int bMax, int cMin) {
        return random -> {
            final int a = between(random, aMin, aMax);
            final int b = between(random, bMin, bMax);
            final int c = between(random, cMin, a + b - 1);
            return new AlarmMathChallenge(a + PLUS + b + MINUS + c, a + b - c);
        };
    }

    /** a × b + c */
    @NonNull
    private static Shape productPlus(int aMin, int aMax, int bMin, int bMax, int cMin, int cMax) {
        return random -> {
            final int a = between(random, aMin, aMax);
            final int b = between(random, bMin, bMax);
            final int c = between(random, cMin, cMax);
            return new AlarmMathChallenge(a + TIMES + b + PLUS + c, a * b + c);
        };
    }

    /** a × b − c, with c below a × b */
    @NonNull
    private static Shape productMinus(int aMin, int aMax, int bMin, int bMax, int cMin, int cMax) {
        return random -> {
            final int a = between(random, aMin, aMax);
            final int b = between(random, bMin, bMax);
            final int product = a * b;
            final int c = between(random, cMin, Math.min(cMax, product - 1));
            return new AlarmMathChallenge(a + TIMES + b + MINUS + c, product - c);
        };
    }

    /** a × b + c × d, both products drawn from the same ranges */
    @NonNull
    private static Shape twoProducts(int factorMin, int factorMax, int multiplierMin, int multiplierMax) {
        return random -> {
            final int a = between(random, factorMin, factorMax);
            final int b = between(random, multiplierMin, multiplierMax);
            final int c = between(random, factorMin, factorMax);
            final int d = between(random, multiplierMin, multiplierMax);
            return new AlarmMathChallenge(a + TIMES + b + PLUS + c + TIMES + d, a * b + c * d);
        };
    }

    /** (a + b) × c, with a and b drawn from the same range */
    @NonNull
    private static Shape sumTimes(int termMin, int termMax, int cMin, int cMax) {
        return random -> {
            final int a = between(random, termMin, termMax);
            final int b = between(random, termMin, termMax);
            final int c = between(random, cMin, cMax);
            return new AlarmMathChallenge("(" + a + PLUS + b + ")" + TIMES + c, (a + b) * c);
        };
    }

    /**
     * @return a random integer in the inclusive range {@code [min, max]}.
     */
    private static int between(@NonNull Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}

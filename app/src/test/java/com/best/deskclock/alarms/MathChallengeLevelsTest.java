// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_MATH_HARDNESS_LEVEL;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_EASY;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_FAIR;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_HARD;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_MODERATE;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_NORMAL;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_TOUGH;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_TRICKY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Random;

public class MathChallengeLevelsTest {

    private static final List<String> LEVELS = List.of(MATH_HARDNESS_LEVEL_EASY, MATH_HARDNESS_LEVEL_FAIR,
        MATH_HARDNESS_LEVEL_MODERATE, MATH_HARDNESS_LEVEL_NORMAL, MATH_HARDNESS_LEVEL_TRICKY,
        MATH_HARDNESS_LEVEL_TOUGH, MATH_HARDNESS_LEVEL_HARD);

    private static final int DRAWS_PER_LEVEL = 20_000;

    @Test
    public void everyLevelIsInTheTable() {
        assertEquals(LEVELS, MathChallengeLevels.LEVELS.stream().map(MathChallengeLevels.Level::key).toList());
    }

    @Test
    public void everyProblemHasTheAnswerItShowsAndFitsTheKeypad() {
        for (String level : LEVELS) {
            Random random = new Random(level.hashCode());
            for (int i = 0; i < DRAWS_PER_LEVEL; i++) {
                AlarmMathChallenge challenge = AlarmMathChallenge.create(level, random);
                String where = level + ": " + challenge.expression();

                assertEquals(where, evaluate(challenge.expression()), challenge.answer());
                assertTrue(where, challenge.answer() >= 0);
                assertTrue(where, String.valueOf(challenge.answer()).length() <= MathMission.MAX_ANSWER_LENGTH);
            }
        }
    }

    @Test
    public void harderLevelsAskMoreProblemsInARow() {
        assertEquals(1, MathChallengeLevels.requiredProblemCount(MATH_HARDNESS_LEVEL_EASY));
        assertEquals(1, MathChallengeLevels.requiredProblemCount(MATH_HARDNESS_LEVEL_FAIR));
        assertEquals(2, MathChallengeLevels.requiredProblemCount(MATH_HARDNESS_LEVEL_MODERATE));
        assertEquals(2, MathChallengeLevels.requiredProblemCount(MATH_HARDNESS_LEVEL_NORMAL));
        assertEquals(2, MathChallengeLevels.requiredProblemCount(MATH_HARDNESS_LEVEL_TRICKY));
        assertEquals(3, MathChallengeLevels.requiredProblemCount(MATH_HARDNESS_LEVEL_TOUGH));
        assertEquals(3, MathChallengeLevels.requiredProblemCount(MATH_HARDNESS_LEVEL_HARD));
    }

    @Test
    public void anUnknownLevelAsksEasyProblems() {
        for (String unknown : List.of(DEFAULT_MATH_HARDNESS_LEVEL, "", "impossible")) {
            assertEquals(1, MathChallengeLevels.requiredProblemCount(unknown));
            Random expected = new Random(7);
            Random actual = new Random(7);
            for (int i = 0; i < 100; i++) {
                assertEquals(AlarmMathChallenge.create(MATH_HARDNESS_LEVEL_EASY, expected),
                    AlarmMathChallenge.create(unknown, actual));
            }
        }
    }

    @Test
    public void matchesOnlyTheAnswer() {
        AlarmMathChallenge challenge = new AlarmMathChallenge("6 × 7", 42);
        assertTrue(challenge.matches("42"));
        assertTrue(challenge.matches(" 42 "));
        assertEquals(false, challenge.matches("41"));
        assertEquals(false, challenge.matches(""));
        assertEquals(false, challenge.matches(null));
        assertEquals(false, challenge.matches("forty-two"));
    }

    /**
     * Evaluates an expression as shown to the user: integers, "+", "−", "×" and parentheses.
     */
    private static int evaluate(String expression) {
        return new Object() {
            final String text = expression.replace(" ", "");
            int pos;

            int parse() {
                int value = sum();
                assertEquals("Unparsed rest of " + expression, text.length(), pos);
                return value;
            }

            int sum() {
                int value = product();
                while (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '−')) {
                    char op = text.charAt(pos++);
                    int right = product();
                    value = op == '+' ? value + right : value - right;
                }
                return value;
            }

            int product() {
                int value = factor();
                while (pos < text.length() && text.charAt(pos) == '×') {
                    pos++;
                    value *= factor();
                }
                return value;
            }

            int factor() {
                if (text.charAt(pos) == '(') {
                    pos++;
                    int value = sum();
                    assertEquals(')', text.charAt(pos++));
                    return value;
                }
                int start = pos;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
                return Integer.parseInt(text.substring(start, pos));
            }
        }.parse();
    }
}

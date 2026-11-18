// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_EASY;
import static com.best.deskclock.settings.PreferencesDefaultValues.MATH_HARDNESS_LEVEL_HARD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.best.deskclock.alarms.MathMission.Result;

import org.junit.Test;

import java.util.Random;

public class MathMissionTest {

    private static void type(MathMission mission, String digits) {
        for (char digit : digits.toCharArray()) {
            mission.typeDigit(digit);
        }
    }

    private static Result answer(MathMission mission, int answer) {
        type(mission, String.valueOf(answer));
        return mission.submit();
    }

    @Test
    public void everyLevelCanBeSolvedOnTheKeypad() {
        for (MathChallengeLevels.Level level : MathChallengeLevels.LEVELS) {
            for (int seed = 0; seed < 2_000; seed++) {
                MathMission mission = new MathMission(level.key(), level.problemCount(), new Random(seed));
                for (int problem = 1; problem < level.problemCount(); problem++) {
                    assertEquals(Result.NEXT_PROBLEM, answer(mission, mission.getChallenge().answer()));
                }
                assertEquals(level.key() + " seed " + seed, Result.SOLVED, answer(mission, mission.getChallenge().answer()));
            }
        }
    }

    @Test
    public void solvesTheRequiredNumberOfProblemsInARow() {
        MathMission mission = new MathMission(MATH_HARDNESS_LEVEL_HARD, 3, new Random(1));
        assertEquals(1, mission.getProblemNumber());

        assertEquals(Result.NEXT_PROBLEM, answer(mission, mission.getChallenge().answer()));
        assertEquals(2, mission.getProblemNumber());
        assertEquals(Result.NEXT_PROBLEM, answer(mission, mission.getChallenge().answer()));
        assertEquals(3, mission.getProblemNumber());
        assertEquals(Result.SOLVED, answer(mission, mission.getChallenge().answer()));
    }

    @Test
    public void alwaysAsksAtLeastOneProblem() {
        MathMission mission = new MathMission(MATH_HARDNESS_LEVEL_EASY, 0, new Random(2));
        assertEquals(1, mission.getRequiredCount());
        assertEquals(Result.SOLVED, answer(mission, mission.getChallenge().answer()));
    }

    @Test
    public void aWrongAnswerIsClearedAndTheProblemStays() {
        MathMission mission = new MathMission(MATH_HARDNESS_LEVEL_EASY, 1, new Random(3));
        AlarmMathChallenge challenge = mission.getChallenge();

        assertEquals(Result.WRONG, answer(mission, challenge.answer() + 1));
        assertFalse(mission.hasAnswer());
        assertEquals(challenge, mission.getChallenge());
        assertEquals(Result.SOLVED, answer(mission, challenge.answer()));
    }

    @Test
    public void submittingNothingDoesNothing() {
        MathMission mission = new MathMission(MATH_HARDNESS_LEVEL_EASY, 1, new Random(4));
        AlarmMathChallenge challenge = mission.getChallenge();

        assertEquals(Result.EMPTY, mission.submit());
        assertEquals(challenge, mission.getChallenge());
    }

    @Test
    public void skippingKeepsTheSolvedProblemsAndShowsAnotherOne() {
        MathMission mission = new MathMission(MATH_HARDNESS_LEVEL_HARD, 3, new Random(5));
        answer(mission, mission.getChallenge().answer());
        AlarmMathChallenge before = mission.getChallenge();
        type(mission, "12");

        mission.skipProblem();

        assertEquals(2, mission.getProblemNumber());
        assertNotEquals(before.expression(), mission.getChallenge().expression());
        assertFalse(mission.hasAnswer());
    }

    @Test
    public void typingRules() {
        MathMission mission = new MathMission(MATH_HARDNESS_LEVEL_EASY, 1, new Random(6));

        type(mission, "0");
        type(mission, "7");
        assertEquals("7", mission.getAnswer().toString());

        type(mission, "123456789");
        assertEquals(MathMission.MAX_ANSWER_LENGTH, mission.getAnswer().length());
        assertEquals("712345", mission.getAnswer().toString());

        mission.deleteDigit();
        assertEquals("71234", mission.getAnswer().toString());

        mission.clearAnswer();
        assertFalse(mission.hasAnswer());
        mission.deleteDigit();
        assertFalse(mission.hasAnswer());

        assertThrows(IllegalArgumentException.class, () -> mission.typeDigit('x'));
        assertTrue(mission.getAnswer().length() == 0);
    }
}

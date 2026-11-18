// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * A math challenge in progress: the problems to solve in a row, and the answer being typed on the
 * keypad. Holds the rules only; the dialog shows it.
 */
public final class MathMission {

    /** Longest answer that can be typed; longer than any answer {@link MathChallengeLevels} produces. */
    public static final int MAX_ANSWER_LENGTH = 6;

    /** What submitting the typed answer did. */
    public enum Result {
        /** Nothing typed: nothing happens. */
        EMPTY,
        /** Wrong answer: it is cleared, the problem stays. */
        WRONG,
        /** Right answer, and more problems to go: the next one is shown. */
        NEXT_PROBLEM,
        /** Right answer to the last problem: the mission is done. */
        SOLVED
    }

    private final String mHardness;
    private final Random mRandom;
    private final int mRequiredCount;
    private final StringBuilder mAnswer = new StringBuilder();
    private AlarmMathChallenge mChallenge;
    private int mSolvedCount;

    /**
     * @param requiredCount how many problems must be solved in a row; at least one is always asked
     */
    public MathMission(@NonNull String hardness, int requiredCount, @NonNull Random random) {
        mHardness = hardness;
        mRandom = random;
        mRequiredCount = Math.max(1, requiredCount);
        newProblem();
    }

    @NonNull
    public AlarmMathChallenge getChallenge() {
        return mChallenge;
    }

    @NonNull
    public CharSequence getAnswer() {
        return mAnswer;
    }

    public boolean hasAnswer() {
        return mAnswer.length() > 0;
    }

    /** @return the 1-based number of the problem being solved. */
    public int getProblemNumber() {
        return mSolvedCount + 1;
    }

    public int getRequiredCount() {
        return mRequiredCount;
    }

    /**
     * Types a digit, unless the answer is already as long as an answer can be. A leading zero is
     * replaced, so "07" can't be typed.
     */
    public void typeDigit(char digit) {
        if (digit < '0' || digit > '9') {
            throw new IllegalArgumentException("Not a digit: " + digit);
        }
        if (mAnswer.length() >= MAX_ANSWER_LENGTH) {
            return;
        }
        if (mAnswer.length() == 1 && mAnswer.charAt(0) == '0') {
            mAnswer.setLength(0);
        }
        mAnswer.append(digit);
    }

    public void deleteDigit() {
        if (hasAnswer()) {
            mAnswer.setLength(mAnswer.length() - 1);
        }
    }

    public void clearAnswer() {
        mAnswer.setLength(0);
    }

    /**
     * Replaces the current problem with a different one, keeping the problems already solved.
     */
    public void skipProblem() {
        newProblem();
    }

    @NonNull
    public Result submit() {
        if (!hasAnswer()) {
            return Result.EMPTY;
        }

        final boolean right = mChallenge.matches(mAnswer.toString());
        clearAnswer();

        if (!right) {
            return Result.WRONG;
        }

        mSolvedCount++;
        if (mSolvedCount < mRequiredCount) {
            newProblem();
            return Result.NEXT_PROBLEM;
        }
        return Result.SOLVED;
    }

    private void newProblem() {
        final AlarmMathChallenge previous = mChallenge;
        do {
            mChallenge = AlarmMathChallenge.create(mHardness, mRandom);
        } while (previous != null && previous.expression().equals(mChallenge.expression()));
        clearAnswer();
    }
}

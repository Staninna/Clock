// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.controller;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.widget.GridLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.best.deskclock.R;
import com.best.deskclock.alarms.AlarmActivity;
import com.best.deskclock.alarms.AlarmMathChallenge;
import com.best.deskclock.databinding.AlarmMathChallengeDialogBinding;
import com.best.deskclock.uicomponents.CustomDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.Random;

/**
 * Shows the math challenge that must be solved before an alarm can be snoozed or dismissed.
 *
 * <p>Depending on the hardness level, one or more problems must be solved in a row. Answers are
 * typed on a keypad that is part of the dialog instead of the system keyboard, so the challenge
 * always works on the lock screen, including before the first unlock after a reboot, when the
 * user's keyboard app may not be available.</p>
 */
public class AlarmMathMissionController {

    /**
     * Longest answer that can be typed; larger than any answer the generator produces.
     */
    private static final int MAX_ANSWER_LENGTH = 6;

    private final Context mContext;
    private final Callback mCallback;
    private final Typeface mGeneralBoldTypeface;
    private AlertDialog mCurrentDialog;

    public AlarmMathMissionController(@NonNull Context context, @NonNull Callback callback, @NonNull Typeface generalBoldTypeface) {
        mContext = context;
        mCallback = callback;
        mGeneralBoldTypeface = generalBoldTypeface;
    }

    public void requestMissionAction(int action, @NonNull String mathHardnessLevel, @NonNull Random random) {
        if (mCurrentDialog != null && mCurrentDialog.isShowing()) {
            return;
        }

        final MissionState state = new MissionState(mathHardnessLevel, random);

        final AlarmMathChallengeDialogBinding binding = AlarmMathChallengeDialogBinding.inflate(LayoutInflater.from(mContext));

        binding.mathChallengeTitle.setTypeface(mGeneralBoldTypeface);
        binding.mathChallengeTitle.setText(mContext.getString(action == AlarmActivity.MISSION_ACTION_SNOOZE
            ? R.string.math_challenge_title_snooze
            : R.string.math_challenge_title_dismiss));

        binding.mathChallengeProgress.setTypeface(mGeneralBoldTypeface);
        binding.mathChallengeText.setTypeface(mGeneralBoldTypeface);
        binding.answerDisplay.setTypeface(mGeneralBoldTypeface);

        binding.mathChallengeError.setVisibility(INVISIBLE);
        binding.mathChallengeError.setTypeface(mGeneralBoldTypeface, Typeface.BOLD);

        buildKeypad(binding, state);
        showCurrentProblem(binding, state);

        mCurrentDialog = CustomDialog.create(
            mContext,
            null,
            null,
            null,
            null,
            binding.getRoot(),
            mContext.getString(android.R.string.ok),
            null,
            mContext.getString(android.R.string.cancel),
            null,
            mContext.getString(R.string.math_challenge_new_problem),
            null,
            alertDialog -> {
                alertDialog.setCancelable(false);
                alertDialog.setCanceledOnTouchOutside(false);

                alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    validateAnswer(binding, state, alertDialog, action));

                // Replace the current problem without losing the problems already solved.
                alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    state.newProblem();
                    showCurrentProblem(binding, state);
                });
            },
            CustomDialog.SoftInputMode.NONE
        );

        mCurrentDialog.setOnDismissListener(dialog -> mCurrentDialog = null);
        mCurrentDialog.show();
    }

    /**
     * Dismisses the challenge if it is showing, e.g. because the alarm was stopped by other means.
     */
    public void dismissMission() {
        if (mCurrentDialog != null) {
            mCurrentDialog.dismiss();
            mCurrentDialog = null;
        }
    }

    private void buildKeypad(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state) {
        final GridLayout keypad = binding.keypad;
        keypad.removeAllViews();

        // 1 2 3 / 4 5 6 / 7 8 9 / C 0 ⌫
        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "DEL"};

        for (String key : keys) {
            final MaterialButton button = new MaterialButton(mContext, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);

            button.setTypeface(mGeneralBoldTypeface);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            button.setInsetTop(0);
            button.setInsetBottom(0);

            switch (key) {
                case "C" -> {
                    button.setText(R.string.math_challenge_clear);
                    button.setContentDescription(mContext.getString(R.string.math_challenge_clear_description));
                    button.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        state.answer.setLength(0);
                        onAnswerChanged(binding, state);
                    });
                }
                case "DEL" -> {
                    button.setIcon(ContextCompat.getDrawable(mContext, R.drawable.ic_backspace));
                    button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
                    button.setIconPadding(0);
                    button.setIconTint(ColorStateList.valueOf(
                        MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary)));
                    button.setContentDescription(mContext.getString(R.string.math_challenge_delete_digit));
                    button.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        if (state.answer.length() > 0) {
                            state.answer.setLength(state.answer.length() - 1);
                            onAnswerChanged(binding, state);
                        }
                    });
                }
                default -> {
                    button.setText(key);
                    button.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        if (state.answer.length() < MAX_ANSWER_LENGTH) {
                            // Avoid leading zeros, e.g. "007".
                            if (state.answer.length() == 1 && state.answer.charAt(0) == '0') {
                                state.answer.setLength(0);
                            }
                            state.answer.append(key);
                            onAnswerChanged(binding, state);
                        }
                    });
                }
            }


            final int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4,
                mContext.getResources().getDisplayMetrics());
            final int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56,
                mContext.getResources().getDisplayMetrics());

            final GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = height;
            params.setMargins(margin, margin, margin, margin);

            keypad.addView(button, params);
        }
    }

    private void showCurrentProblem(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state) {
        binding.mathChallengeText.setText(mContext.getString(R.string.math_challenge_expression, state.challenge.expression()));

        if (state.requiredCount > 1) {
            binding.mathChallengeProgress.setVisibility(VISIBLE);
            binding.mathChallengeProgress.setText(mContext.getString(R.string.math_challenge_progress,
                state.solvedCount + 1, state.requiredCount));
        } else {
            binding.mathChallengeProgress.setVisibility(GONE);
        }

        state.answer.setLength(0);
        onAnswerChanged(binding, state);
    }

    private void onAnswerChanged(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state) {
        binding.answerDisplay.setText(state.answer);
        binding.mathChallengeError.setVisibility(INVISIBLE);
    }

    private void validateAnswer(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state,
                                @NonNull AlertDialog dialog, int action) {

        if (state.answer.length() == 0) {
            return;
        }

        if (!state.challenge.matches(state.answer.toString())) {
            binding.mathChallengeError.setVisibility(VISIBLE);
            binding.answerDisplay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            state.answer.setLength(0);
            binding.answerDisplay.setText(state.answer);
            return;
        }

        state.solvedCount++;

        if (state.solvedCount < state.requiredCount) {
            state.newProblem();
            showCurrentProblem(binding, state);
            return;
        }

        dialog.dismiss();
        mCurrentDialog = null;
        mCallback.onMissionResolved(action);
    }

    /**
     * Progress of the user through the current mission.
     */
    private static final class MissionState {
        final String hardness;
        final Random random;
        final int requiredCount;
        final StringBuilder answer = new StringBuilder();
        AlarmMathChallenge challenge;
        int solvedCount;

        MissionState(@NonNull String hardness, @NonNull Random random) {
            this.hardness = hardness;
            this.random = random;
            this.requiredCount = AlarmMathChallenge.requiredProblemCount(hardness);
            newProblem();
        }

        void newProblem() {
            final AlarmMathChallenge previous = challenge;
            do {
                challenge = AlarmMathChallenge.create(hardness, random);
            } while (previous != null && previous.expression().equals(challenge.expression()));
        }
    }

    public interface Callback {
        void onMissionResolved(int action);
    }

}

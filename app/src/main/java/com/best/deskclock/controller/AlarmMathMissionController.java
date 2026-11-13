// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.controller;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static androidx.core.util.TypedValueCompat.dpToPx;

import android.content.Context;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
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
import com.best.deskclock.utils.Utils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.shape.ShapeAppearanceModel;

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

    /**
     * Fully round keypad keys, without a separate pressed shape.
     */
    private static final ShapeAppearanceModel ROUND_KEY_SHAPE =
        ShapeAppearanceModel.builder().setAllCornerSizes(ShapeAppearanceModel.PILL).build();

    private final Context mContext;
    private final Callback mCallback;
    private final Typeface mGeneralBoldTypeface;
    private final DisplayMetrics mDisplayMetrics;
    private final boolean mIsVibrationEnabled;
    private AlertDialog mCurrentDialog;

    public AlarmMathMissionController(@NonNull Context context, @NonNull Typeface generalBoldTypeface,
                                      @NonNull DisplayMetrics displayMetrics, boolean isVibrationEnabled, @NonNull Callback callback) {
        mContext = context;
        mGeneralBoldTypeface = generalBoldTypeface;
        mDisplayMetrics = displayMetrics;
        mIsVibrationEnabled = isVibrationEnabled;
        mCallback = callback;
    }

    /**
     * @param problemCount how many problems must be solved in a row (see
     *                     {@link com.best.deskclock.utils.AlarmUtils#getEffectiveMathProblemCount}).
     */
    public void requestMissionAction(int action, @NonNull String mathHardnessLevel, int problemCount, @NonNull Random random) {
        if (mCurrentDialog != null && mCurrentDialog.isShowing()) {
            return;
        }

        final MissionState state = new MissionState(mathHardnessLevel, Math.max(1, problemCount), random);

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

        final Keypad keypad = buildKeypad(binding, state);
        showCurrentProblem(binding, state, keypad);

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
            (d, w) -> {
                mCurrentDialog = null;
                mCallback.onMissionCancelled();
            },
            mContext.getString(R.string.math_challenge_new_problem),
            null,
            alertDialog -> {
                alertDialog.setCancelable(false);
                alertDialog.setCanceledOnTouchOutside(false);

                alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    validateAnswer(binding, state, keypad, alertDialog, action));

                // Replace the current problem without losing the problems already solved.
                alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    state.newProblem();
                    showCurrentProblem(binding, state, keypad);
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

    @NonNull
    private Keypad buildKeypad(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state) {
        final GridLayout keypadLayout = binding.mathChallengeKeypad;
        keypadLayout.removeAllViews();

        // 1 2 3
        // 4 5 6
        // 7 8 9
        // C 0 ⌫
        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "CLEAR", "0", "DELETE"};

        final Keypad keypad = new Keypad();

        for (String key : keys) {
            final MaterialButton button =
                new MaterialButton(mContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);

            button.setStrokeColorResource(R.color.icon_tint);
            button.setIconTintResource(R.color.icon_tint);
            button.setTextColor(ContextCompat.getColorStateList(mContext, R.color.icon_tint));
            button.setTypeface(mGeneralBoldTypeface);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            // Material 3 Expressive buttons morph to squarer corners while pressed, which looks like
            // flicker when typing on a keypad. Use one fixed, fully round shape instead.
            button.setShapeAppearanceModel(ROUND_KEY_SHAPE);

            switch (key) {
                case "CLEAR" -> {
                    keypad.clearButton = button;

                    button.setIcon(ContextCompat.getDrawable(mContext, R.drawable.ic_clear));
                    button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
                    button.setIconPadding(0);
                    button.setIconSize((int) dpToPx(32, mDisplayMetrics));
                    button.setContentDescription(mContext.getString(R.string.math_challenge_clear_description));
                    button.setOnClickListener(v -> {
                        Utils.performHapticFeedback(v, mIsVibrationEnabled, HapticFeedbackConstants.CLOCK_TICK);
                        state.answer.setLength(0);
                        onAnswerChanged(binding, state, keypad);
                    });
                }

                case "DELETE" -> {
                    keypad.deleteButton = button;

                    button.setIcon(ContextCompat.getDrawable(mContext, R.drawable.ic_backspace));
                    button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
                    button.setIconPadding(0);
                    button.setIconSize((int) dpToPx(32, mDisplayMetrics));
                    button.setContentDescription(mContext.getString(R.string.math_challenge_delete_digit));
                    button.setOnClickListener(v -> {
                        Utils.performHapticFeedback(v, mIsVibrationEnabled, HapticFeedbackConstants.CLOCK_TICK);
                        if (state.answer.length() > 0) {
                            state.answer.setLength(state.answer.length() - 1);
                            onAnswerChanged(binding, state, keypad);
                        }
                    });
                }

                default -> {
                    button.setText(key);
                    button.setOnClickListener(v -> {
                        Utils.performHapticFeedback(v, mIsVibrationEnabled, HapticFeedbackConstants.CLOCK_TICK);
                        if (state.answer.length() < MAX_ANSWER_LENGTH) {
                            // Avoid leading zeros, e.g. "007".
                            if (state.answer.length() == 1 && state.answer.charAt(0) == '0') {
                                state.answer.setLength(0);
                            }
                            state.answer.append(key);
                            onAnswerChanged(binding, state, keypad);
                        }
                    });
                }
            }

            final int margin = (int) dpToPx(4, mDisplayMetrics);
            final int height = (int) dpToPx(56, mDisplayMetrics);

            final GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));

            params.width = 0;
            params.height = height;
            params.setMargins(margin, margin, margin, margin);

            keypadLayout.addView(button, params);
        }

        return keypad;
    }

    private void showCurrentProblem(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state,
                                    @NonNull Keypad keypad) {

        binding.mathChallengeText.setText(mContext.getString(R.string.math_challenge_expression, state.challenge.expression()));

        if (state.requiredCount > 1) {
            binding.mathChallengeProgress.setVisibility(VISIBLE);
            binding.mathChallengeProgress.setText(mContext.getString(R.string.math_challenge_progress,
                state.solvedCount + 1, state.requiredCount));
        } else {
            binding.mathChallengeProgress.setVisibility(GONE);
        }

        state.answer.setLength(0);
        onAnswerChanged(binding, state, keypad);
    }

    private void onAnswerChanged(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state,
                                 @NonNull Keypad keypad) {

        binding.answerDisplay.setText(state.answer);
        binding.mathChallengeError.setVisibility(INVISIBLE);
        keypad.updateEnabledState(state.answer.length() > 0);
    }

    private void validateAnswer(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MissionState state,
                                @NonNull Keypad keypad, @NonNull AlertDialog dialog, int action) {

        if (state.answer.length() == 0) {
            return;
        }

        if (!state.challenge.matches(state.answer.toString())) {
            binding.mathChallengeError.setVisibility(VISIBLE);
            Utils.performHapticFeedback(binding.answerDisplay, mIsVibrationEnabled, HapticFeedbackConstants.LONG_PRESS);
            state.answer.setLength(0);
            binding.answerDisplay.setText(state.answer);
            keypad.updateEnabledState(false);
            return;
        }

        state.solvedCount++;

        if (state.solvedCount < state.requiredCount) {
            state.newProblem();
            showCurrentProblem(binding, state, keypad);
            return;
        }

        dialog.dismiss();
        mCurrentDialog = null;
        mCallback.onMissionResolved(action);
    }

    /**
     * The keypad keys that are only enabled while an answer is being typed.
     */
    private static final class Keypad {
        MaterialButton clearButton;
        MaterialButton deleteButton;

        void updateEnabledState(boolean hasAnswer) {
            if (clearButton != null) {
                clearButton.setEnabled(hasAnswer);
            }

            if (deleteButton != null) {
                deleteButton.setEnabled(hasAnswer);
            }
        }
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

        MissionState(@NonNull String hardness, int requiredCount, @NonNull Random random) {
            this.hardness = hardness;
            this.random = random;
            this.requiredCount = requiredCount;
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
        void onMissionCancelled();
    }

}

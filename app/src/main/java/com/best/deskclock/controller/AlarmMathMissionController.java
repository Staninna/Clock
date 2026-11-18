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
import com.best.deskclock.alarms.MathMission;
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
    private final Context mContext;
    private final Callback mCallback;
    private final Typeface mGeneralBoldTypeface;
    private final DisplayMetrics mDisplayMetrics;
    private final boolean mIsVibrationEnabled;
    private AlertDialog mCurrentDialog;

    /**
     * Fully round keypad keys, without a separate pressed shape.
     */
    private static final ShapeAppearanceModel ROUND_KEY_SHAPE =
        ShapeAppearanceModel.builder().setAllCornerSizes(ShapeAppearanceModel.PILL).build();

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

        final MathMission mission = new MathMission(mathHardnessLevel, problemCount, random);

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

        final Keypad keypad = buildKeypad(binding, mission);
        showCurrentProblem(binding, mission, keypad);

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
                    validateAnswer(binding, mission, keypad, alertDialog, action));

                // Replace the current problem without losing the problems already solved.
                alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    mission.skipProblem();
                    showCurrentProblem(binding, mission, keypad);
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
    private Keypad buildKeypad(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MathMission mission) {
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
                        mission.clearAnswer();
                        onAnswerChanged(binding, mission, keypad);
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
                        mission.deleteDigit();
                        onAnswerChanged(binding, mission, keypad);
                    });
                }

                default -> {
                    button.setText(key);
                    button.setOnClickListener(v -> {
                        Utils.performHapticFeedback(v, mIsVibrationEnabled, HapticFeedbackConstants.CLOCK_TICK);
                        mission.typeDigit(key.charAt(0));
                        onAnswerChanged(binding, mission, keypad);
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

    private void showCurrentProblem(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MathMission mission,
                                    @NonNull Keypad keypad) {

        binding.mathChallengeText.setText(mContext.getString(R.string.math_challenge_expression,
            mission.getChallenge().expression()));

        if (mission.getRequiredCount() > 1) {
            binding.mathChallengeProgress.setVisibility(VISIBLE);
            binding.mathChallengeProgress.setText(mContext.getString(R.string.math_challenge_progress,
                mission.getProblemNumber(), mission.getRequiredCount()));
        } else {
            binding.mathChallengeProgress.setVisibility(GONE);
        }

        onAnswerChanged(binding, mission, keypad);
    }

    private void onAnswerChanged(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MathMission mission,
                                 @NonNull Keypad keypad) {

        binding.answerDisplay.setText(mission.getAnswer());
        binding.mathChallengeError.setVisibility(INVISIBLE);
        keypad.updateEnabledState(mission.hasAnswer());
    }

    private void validateAnswer(@NonNull AlarmMathChallengeDialogBinding binding, @NonNull MathMission mission,
                                @NonNull Keypad keypad, @NonNull AlertDialog dialog, int action) {

        switch (mission.submit()) {
            case EMPTY -> {
            }
            case WRONG -> {
                binding.mathChallengeError.setVisibility(VISIBLE);
                Utils.performHapticFeedback(binding.answerDisplay, mIsVibrationEnabled, HapticFeedbackConstants.LONG_PRESS);
                binding.answerDisplay.setText(mission.getAnswer());
                keypad.updateEnabledState(false);
            }
            case NEXT_PROBLEM -> showCurrentProblem(binding, mission, keypad);
            case SOLVED -> {
                dialog.dismiss();
                mCurrentDialog = null;
                mCallback.onMissionResolved(action);
            }
        }
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

    public interface Callback {
        void onMissionResolved(int action);
        void onMissionCancelled();
    }

}

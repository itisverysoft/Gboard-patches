package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The panel shown over the keyboard while dictating to the Mac: what has been
 * heard so far, a microphone level dot, the language, and Cancel / Send ↵ / Send.
 * It swallows touches so the keys underneath can't be pressed. Main thread only.
 */
final class MacBridgeDictationPanel extends FrameLayout {
    interface Actions {
        void onCancel();

        void onSend(boolean pressEnter);

        void onToggleLanguage();
    }

    private static final int MAX_TRANSCRIPT_CHARS = 400;

    private final TextView status;
    private final TextView language;
    private final TextView transcript;
    private final View levelDot;
    private final TextView cancel;
    private final TextView sendEnter;
    private final TextView send;

    MacBridgeDictationPanel(Context context, Actions actions, String hint, String cancelLabel,
            String sendEnterLabel, String sendLabel) {
        super(context);
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int background = night ? 0xFF1F2023 : 0xFFF1F3F4;
        int primary = night ? 0xFFE8EAED : 0xFF202124;
        int secondary = night ? 0xFFBDC1C6 : 0xFF5F6368;
        int accent = night ? 0xFF8AB4F8 : 0xFF1A73E8;
        int onAccent = night ? 0xFF202124 : 0xFFFFFFFF;
        int tonal = night ? 0xFF303134 : 0xFFE2E6EA;

        setBackgroundColor(background);
        setClickable(true);
        setFocusable(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(12));
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        levelDot = new View(context);
        levelDot.setBackground(rounded(accent, dp(6), 0, 0));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMarginEnd(dp(10));
        header.addView(levelDot, dotParams);

        status = new TextView(context);
        status.setTextColor(secondary);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        language = new TextView(context);
        language.setTextColor(accent);
        language.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        language.setTypeface(Typeface.DEFAULT_BOLD);
        language.setGravity(Gravity.CENTER);
        language.setMinWidth(dp(48));
        language.setPadding(dp(12), dp(6), dp(12), dp(6));
        language.setBackground(rounded(0, dp(16), dp(1), accent));
        language.setOnClickListener(view -> actions.onToggleLanguage());
        header.addView(language, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        transcript = new TextView(context);
        transcript.setTextColor(primary);
        transcript.setHintTextColor(secondary);
        transcript.setHint(hint);
        transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        transcript.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        transcript.setMaxLines(4);
        transcript.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        transcriptParams.topMargin = dp(8);
        transcriptParams.bottomMargin = dp(8);
        column.addView(transcript, transcriptParams);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        cancel = button(context, cancelLabel, primary, rounded(0, dp(24), dp(1), secondary));
        cancel.setOnClickListener(view -> actions.onCancel());
        sendEnter = button(context, sendEnterLabel, primary, rounded(tonal, dp(24), 0, 0));
        sendEnter.setOnClickListener(view -> actions.onSend(true));
        send = button(context, sendLabel, onAccent, rounded(accent, dp(24), 0, 0));
        send.setOnClickListener(view -> actions.onSend(false));
        addButton(buttons, cancel, false);
        addButton(buttons, sendEnter, true);
        addButton(buttons, send, true);
    }

    void setStatus(String text) {
        status.setText(text);
    }

    void setLanguageLabel(String label) {
        language.setText(label);
    }

    void setTranscript(String text) {
        String value = text == null ? "" : text;
        if (value.length() > MAX_TRANSCRIPT_CHARS) {
            value = "…" + value.substring(value.length() - MAX_TRANSCRIPT_CHARS);
        }
        transcript.setText(value);
    }

    /** 0 (silence) to 1 (loud). */
    void setLevel(float level) {
        float scale = 1f + Math.max(0f, Math.min(1f, level)) * 0.9f;
        levelDot.setScaleX(scale);
        levelDot.setScaleY(scale);
    }

    /** While the final words are being recognized: buttons off, status says so. */
    void setBusy(String statusText) {
        status.setText(statusText);
        setLevel(0f);
        for (View view : new View[]{cancel, sendEnter, send, language}) {
            view.setEnabled(false);
            view.setAlpha(0.5f);
        }
    }

    private static TextView button(Context context, String label, int textColor,
            GradientDrawable background) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setBackground(background);
        return button;
    }

    private void addButton(LinearLayout row, TextView button, boolean withGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (withGap) {
            params.setMarginStart(dp(8));
        }
        row.addView(button, params);
    }

    private static GradientDrawable rounded(int fill, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}

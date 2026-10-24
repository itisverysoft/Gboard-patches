package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Speech-to-text through Android's Google speech recognizer, ported from Rambler's
 * GoogleSpeechEngine. It keeps listening across pauses until [stop]: Google ends a
 * session at every pause, so each session's result is appended to what was said
 * before it. Main thread only; SpeechRecognizer must be used from the thread that
 * created it.
 */
final class MacBridgeSpeech {
    private static final String TAG = "GboardMacBridge";

    static final String LANGUAGE_ENGLISH = "en";
    static final String LANGUAGE_BENGALI = "bn";

    interface Callback {
        /** Everything heard so far, including the words still being recognized. */
        void onPartial(String text);

        /** The dictation ended; null when nothing was heard. */
        void onFinal(String text);

        void onError(int code);

        /** Microphone level, 0 (silence) to 1 (loud). */
        void onLevel(float level);
    }

    private final Context context;
    private SpeechRecognizer recognizer;
    private Intent intent;
    private Callback callback;
    private boolean continuous;
    private String committed = "";

    MacBridgeSpeech(Context context) {
        this.context = context;
    }

    static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    void start(String language, Callback callback) {
        cancel();
        this.callback = callback;
        this.continuous = true;
        this.committed = "";
        String locale = localeFor(language);
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale);
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        request.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Punctuated, capitalized text, since the Mac types it verbatim.
            request.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
        }
        intent = request;
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(listener);
        recognizer.startListening(request);
        Log.i(TAG, "Dictation started, language=" + locale);
    }

    /** Stops capturing but still delivers whatever was heard. */
    void stop() {
        continuous = false;
        if (recognizer != null) {
            recognizer.stopListening();
        }
    }

    /** Drops the dictation without a result. */
    void cancel() {
        callback = null;
        continuous = false;
        committed = "";
        intent = null;
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // Already disconnected from the recognition service.
            }
            recognizer = null;
        }
    }

    boolean isActive() {
        return recognizer != null;
    }

    static String localeFor(String language) {
        return LANGUAGE_BENGALI.equals(language) ? "bn-BD" : "en-US";
    }

    /** Appends one recognition session's text to the dictation so far. */
    static String join(String before, String next) {
        String first = before == null ? "" : before.trim();
        String second = next == null ? "" : next.trim();
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return first + " " + second;
    }

    /** "Nothing was said" cases are not failures. */
    static boolean isNothingHeard(int code) {
        return code == SpeechRecognizer.ERROR_NO_MATCH
                || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
    }

    private void finish(String result) {
        Callback deliver = callback;
        cancel();
        String text = result == null ? null : result.trim();
        if (deliver != null) {
            deliver.onFinal(text == null || text.isEmpty() ? null : text);
        }
    }

    private void fail(int code) {
        Callback deliver = callback;
        cancel();
        Log.w(TAG, "Speech recognition error " + code);
        if (deliver != null) {
            deliver.onError(code);
        }
    }

    private static String first(Bundle results) {
        if (results == null) {
            return null;
        }
        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty() || texts.get(0) == null) {
            return null;
        }
        String text = texts.get(0).trim();
        return text.isEmpty() ? null : text;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            Callback current = callback;
            if (current != null) {
                // Google reports roughly -2 dB (silence) to 10 dB (loud).
                current.onLevel(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f)));
            }
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            if (continuous && intent != null && isNothingHeard(error)) {
                // A silence timeout while the user is still dictating: keep listening.
                recognizer.startListening(intent);
                return;
            }
            // Never lose what was already recognized, even if the last session failed.
            if (!committed.isEmpty()) {
                finish(committed);
                return;
            }
            if (isNothingHeard(error)) {
                finish(null);
                return;
            }
            fail(error);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = first(partialResults);
            Callback current = callback;
            if (text != null && current != null) {
                current.onPartial(join(committed, text));
            }
        }

        @Override
        public void onResults(Bundle results) {
            String text = first(results);
            if (continuous && intent != null) {
                if (text != null) {
                    committed = join(committed, text);
                    Callback current = callback;
                    if (current != null) {
                        current.onPartial(committed);
                    }
                }
                recognizer.startListening(intent);
                return;
            }
            finish(join(committed, text));
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}

package dev.jason.gboardpatches.extension.macbridge;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Proxy;

@RunWith(RobolectricTestRunner.class)
public final class GboardMacBridgeRuntimeTest {
    @Test
    public void passwordFieldsAreNeverSent() {
        Assert.assertTrue(GboardMacBridgeRuntime.isPasswordField(editor(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)));
        Assert.assertTrue(GboardMacBridgeRuntime.isPasswordField(editor(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)));
        Assert.assertTrue(GboardMacBridgeRuntime.isPasswordField(editor(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)));
        Assert.assertTrue(GboardMacBridgeRuntime.isPasswordField(editor(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)));
        Assert.assertFalse(GboardMacBridgeRuntime.isPasswordField(editor(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)));
        Assert.assertFalse(GboardMacBridgeRuntime.isPasswordField(editor(
                InputType.TYPE_CLASS_NUMBER)));
        Assert.assertFalse(GboardMacBridgeRuntime.isPasswordField(null));
    }

    @Test
    public void selectionWinsOverTheWholeField() {
        InputConnection connection = connection("selected", "whole field", null, null);

        Assert.assertEquals("selected", GboardMacBridgeRuntime.readFieldText(connection));
    }

    @Test
    public void wholeFieldIsSentWhenNothingIsSelected() {
        InputConnection extracted = connection("", "whole field", null, null);
        InputConnection aroundCursor = connection(null, null, "before ", "after");

        Assert.assertEquals("whole field", GboardMacBridgeRuntime.readFieldText(extracted));
        Assert.assertEquals("before after", GboardMacBridgeRuntime.readFieldText(aroundCursor));
    }

    @Test
    public void emptyExtractFallsBackToTheTextAroundTheCursor() {
        InputConnection webView = connection(null, "", "typed in ", "a web view");

        Assert.assertEquals("typed in a web view", GboardMacBridgeRuntime.readFieldText(webView));
    }

    @Test
    public void longTextIsCutWithoutSplittingASurrogatePair() {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 39_999) {
            builder.append('a');
        }
        builder.append("😀").append("tail");

        String limited = GboardMacBridgeRuntime.limit(builder.toString());

        Assert.assertEquals(39_999, limited.length());
        Assert.assertFalse(Character.isHighSurrogate(limited.charAt(limited.length() - 1)));
        Assert.assertEquals("short", GboardMacBridgeRuntime.limit("short"));
    }

    @Test
    public void unchangedFieldIsClearedAfterSending() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        InputConnection connection = recording(calls, null, "hello mac", "hello ", "mac");

        Assert.assertTrue(GboardMacBridgeRuntime.clearIfUnchanged(connection, "hello mac", false));
        Assert.assertTrue(calls.contains("finishComposingText"));
        Assert.assertTrue(calls.contains("deleteSurroundingText:6,3"));
    }

    @Test
    public void editedFieldIsLeftAlone() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        InputConnection connection = recording(calls, null, "hello mac, more", "", "");

        Assert.assertFalse(GboardMacBridgeRuntime.clearIfUnchanged(connection, "hello mac", false));
        Assert.assertFalse(calls.stream().anyMatch(call -> call.startsWith("delete")));
    }

    @Test
    public void onlyTheSentSelectionIsRemoved() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        InputConnection sameSelection = recording(calls, "mac", "hello mac", "hello ", "");
        InputConnection movedSelection = recording(calls, "hello", "hello mac", "", "");

        Assert.assertTrue(GboardMacBridgeRuntime.clearIfUnchanged(sameSelection, "mac", true));
        Assert.assertTrue(calls.contains("commitText:"));
        calls.clear();
        Assert.assertFalse(GboardMacBridgeRuntime.clearIfUnchanged(movedSelection, "mac", true));
        Assert.assertTrue(calls.isEmpty());
    }

    private static InputConnection recording(java.util.List<String> calls, String selected,
            String extracted, String before, String after) {
        return (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(),
                new Class<?>[]{InputConnection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSelectedText":
                            return selected;
                        case "getExtractedText":
                            ExtractedText text = new ExtractedText();
                            text.text = extracted;
                            return text;
                        case "getTextBeforeCursor":
                            return before;
                        case "getTextAfterCursor":
                            return after;
                        case "commitText":
                            calls.add("commitText:" + args[0]);
                            return Boolean.TRUE;
                        case "deleteSurroundingText":
                            calls.add("deleteSurroundingText:" + args[0] + "," + args[1]);
                            return Boolean.TRUE;
                        case "finishComposingText":
                            calls.add("finishComposingText");
                            return Boolean.TRUE;
                        default:
                            Class<?> type = method.getReturnType();
                            return type == boolean.class ? Boolean.TRUE
                                    : type == int.class ? 0 : null;
                    }
                });
    }

    private static EditorInfo editor(int inputType) {
        EditorInfo info = new EditorInfo();
        info.inputType = inputType;
        return info;
    }

    private static InputConnection connection(String selected, String extracted,
            String before, String after) {
        return (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(),
                new Class<?>[]{InputConnection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSelectedText":
                            return selected;
                        case "getExtractedText":
                            if (extracted == null) {
                                return null;
                            }
                            ExtractedText text = new ExtractedText();
                            text.text = extracted;
                            return text;
                        case "getTextBeforeCursor":
                            return before;
                        case "getTextAfterCursor":
                            return after;
                        default:
                            Class<?> type = method.getReturnType();
                            return type == boolean.class ? Boolean.FALSE
                                    : type == int.class ? 0 : null;
                    }
                });
    }
}

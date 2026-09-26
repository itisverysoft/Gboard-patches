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

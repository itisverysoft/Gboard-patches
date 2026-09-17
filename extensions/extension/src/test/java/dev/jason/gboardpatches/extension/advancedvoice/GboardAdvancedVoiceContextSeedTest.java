package dev.jason.gboardpatches.extension.advancedvoice;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public final class GboardAdvancedVoiceContextSeedTest {
    @After
    public void tearDown() {
        GboardAdvancedVoice1803RuntimeSettings.clearEnabledOverrideForTest();
    }

    @Test
    public void constructorContextMakesTheFirstVoiceDecisionUsePersistedSettings() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = GboardAdvancedVoiceSettings.preferences(context);
        preferences.edit()
                .putBoolean(GboardAdvancedVoiceSettings.PREF_KEY_ENABLED, true)
                .putBoolean(
                        GboardAdvancedVoiceSettings.PREF_KEY_ZH_TW_PUNCTUATION_ENABLED,
                        true)
                .commit();

        GboardAdvancedVoice1803RuntimeSettings.clearEnabledOverrideForTest();
        GboardAdvancedVoice1803Runtime.seedApplicationContext(context);

        Assert.assertEquals(
                Boolean.TRUE,
                GboardAdvancedVoice1803Runtime.afterFlagValue(
                        new FlagReceiver("enable_nga"),
                        Boolean.FALSE));
        Set<?> locales = (Set<?>) GboardAdvancedVoice1803Runtime
                .includeExactZhTwSupportedLocale(Collections.emptySet());
        Assert.assertTrue(locales.contains(Locale.forLanguageTag("zh-TW")));
    }

    @Test
    public void bengaliSettingAdmitsBengaliLocalesOnlyWhenEnabled() {
        GboardAdvancedVoice1803RuntimeSettings.setEnabledOverrideForTest(true);
        GboardAdvancedVoice1803RuntimeSettings.setZhTwPunctuationEnabledOverrideForTest(false);
        GboardAdvancedVoice1803RuntimeSettings.setBengaliEnabledOverrideForTest(false);
        Set<?> stock = Collections.singleton(Locale.US);
        Assert.assertSame(stock,
                GboardAdvancedVoice1803Runtime.includeExactZhTwSupportedLocale(stock));

        GboardAdvancedVoice1803RuntimeSettings.setBengaliEnabledOverrideForTest(true);
        Set<?> locales = (Set<?>) GboardAdvancedVoice1803Runtime
                .includeExactZhTwSupportedLocale(stock);
        Assert.assertTrue(locales.contains(Locale.US));
        Assert.assertTrue(locales.contains(Locale.forLanguageTag("bn-BD")));
        Assert.assertTrue(locales.contains(Locale.forLanguageTag("bn-IN")));
        Assert.assertFalse(locales.contains(Locale.forLanguageTag("zh-TW")));
    }

    @Test
    public void nullConstructorContextIsSafe() {
        GboardAdvancedVoice1803Runtime.seedApplicationContext(null);
    }

    private static final class FlagReceiver {
        private final String a;

        private FlagReceiver(String flagName) {
            a = flagName;
        }
    }
}

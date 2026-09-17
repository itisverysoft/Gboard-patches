package dev.jason.gboardpatches.extension.advancedvoice;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class GboardAdvancedVoice1803Policy {
    public static final String ENABLE_NGA_FLAG = "enable_nga";
    public static final String ENABLE_ADVANCED_FEATURES_FLAG =
            "enable_advanced_features_in_consolidated_sd_stack";
    public static final String ENABLE_DICTATION_SPLIT_INSTALL_FLAG =
            "enable_dictation_feature_split_install";
    public static final String HANDLE_FALLBACK_INSIDE_SD_STACK_FLAG =
            "handle_fallback_inside_sd_stack";
    public static final String ENABLE_STICKY_MIC_BACKGROUND_FLAG =
            "enable_sticky_mic_background";
    public static final String ENABLE_SODA_LONGFORM_EXPERIMENT_FLAG =
            "enable_soda_longform_experiment";

    public static final Set<String> ZH_TW_LANGUAGE_TAGS =
            Collections.singleton("zh-TW");
    /** Bengali locales admitted to the stock Advanced Voice / Rambler pipeline. */
    public static final Set<String> BENGALI_LANGUAGE_TAGS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("bn-BD", "bn-IN")));

    private GboardAdvancedVoice1803Policy() {
    }

    public static Object maybeForceFlag(String flagName, Object originalResult) {
        if (!isAdvancedVoiceFlag(flagName)
                || !(originalResult instanceof Boolean)
                || Boolean.TRUE.equals(originalResult)) {
            return originalResult;
        }
        return Boolean.TRUE;
    }

    public static boolean shouldPromoteNativeReadiness(
            Object originalResult,
            boolean nativeLoaded) {
        return Boolean.FALSE.equals(originalResult) && nativeLoaded;
    }

    public static Object maybeEnableExactZhTwFormatter(
            Locale locale,
            boolean stockAdvancedFeaturesDisabled,
            Object originalFormatterDisabled) {
        return maybeEnableFormatterForLanguageTags(
                locale,
                ZH_TW_LANGUAGE_TAGS,
                stockAdvancedFeaturesDisabled,
                originalFormatterDisabled);
    }

    public static Object maybeEnableFormatterForLanguageTags(
            Locale locale,
            Set<String> allowedLanguageTags,
            boolean stockAdvancedFeaturesDisabled,
            Object originalFormatterDisabled) {
        if (locale == null
                || allowedLanguageTags == null
                || !allowedLanguageTags.contains(locale.toLanguageTag())
                || stockAdvancedFeaturesDisabled
                || !Boolean.TRUE.equals(originalFormatterDisabled)) {
            return originalFormatterDisabled;
        }
        return Boolean.FALSE;
    }

    private static boolean isAdvancedVoiceFlag(String flagName) {
        return ENABLE_NGA_FLAG.equals(flagName)
                || ENABLE_ADVANCED_FEATURES_FLAG.equals(flagName)
                || ENABLE_DICTATION_SPLIT_INSTALL_FLAG.equals(flagName)
                || HANDLE_FALLBACK_INSIDE_SD_STACK_FLAG.equals(flagName)
                || ENABLE_STICKY_MIC_BACKGROUND_FLAG.equals(flagName)
                || ENABLE_SODA_LONGFORM_EXPERIMENT_FLAG.equals(flagName);
    }
}

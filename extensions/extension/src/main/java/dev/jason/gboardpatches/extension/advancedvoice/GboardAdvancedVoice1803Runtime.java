package dev.jason.gboardpatches.extension.advancedvoice;

import android.content.Context;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.jason.gboardpatches.extension.rambler.GboardRambler1803StockPolicy;

public final class GboardAdvancedVoice1803Runtime {
    private static final String TAG = "GboardPatches";
    private static final String LOG_PREFIX = "[gboard-advanced-voice-18.0.3] ";
    private static final int ADVANCED_VOICE_PREFERENCE_KEY = 0x7f140971;
    private static final int AUTO_PUNCTUATION_PREFERENCE_KEY = 0x7f140972;

    private static final AtomicBoolean ZH_TW_MDD_REQUESTED = new AtomicBoolean(false);
    private static final Map<String, AtomicBoolean> BENGALI_MDD_REQUESTED =
            new ConcurrentHashMap<String, AtomicBoolean>();
    private static final AtomicInteger INFO_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger ERROR_LOG_COUNT = new AtomicInteger();
    private static final ConcurrentHashMap<Class<?>, Field> FLAG_NAME_FIELDS =
            new ConcurrentHashMap<Class<?>, Field>();
    private static final Map<ClassLoader, WeakReference<Handles>> HANDLES_BY_LOADER =
            new WeakHashMap<ClassLoader, WeakReference<Handles>>();

    private GboardAdvancedVoice1803Runtime() {
    }

    public static void seedApplicationContext(Context context) {
        try {
            GboardAdvancedVoice1803RuntimeSettings.seedApplicationContext(context);
        } catch (Throwable failure) {
            logError("constructor context seed failed", failure);
        }
    }

    public static Object afterFlagValue(Object receiver, Object stockResult) {
        if (!GboardAdvancedVoice1803RuntimeSettings.isEnabled()) {
            return stockResult;
        }
        try {
            String flagName = readFlagName(receiver);
            Object result = GboardAdvancedVoice1803StockPolicy.maybeForceStockFlag(
                    flagName, stockResult);
            result = GboardRambler1803StockPolicy.maybeForceStockRouteValue(flagName, result);
            if (Boolean.FALSE.equals(stockResult) && Boolean.TRUE.equals(result)) {
                logInfo("forced " + flagName + " via nxw#g()");
            }
            return result;
        } catch (Throwable failure) {
            logError("flag override failed", failure);
            return stockResult;
        }
    }

    public static boolean afterNativeReadiness(boolean stockResult) {
        if (stockResult || !GboardAdvancedVoice1803RuntimeSettings.isEnabled()) {
            return stockResult;
        }
        try {
            Handles handles = handles(runtimeClassLoader());
            boolean nativeLoaded = readMemoizedNativeReadiness(
                    handles.nativeReadinessSupplierField,
                    handles.nativeReadinessSupplierMethod);
            boolean promoted = GboardAdvancedVoice1803Policy.shouldPromoteNativeReadiness(
                    Boolean.valueOf(stockResult), nativeLoaded);
            if (promoted) {
                logInfo("promoted ric#a() after memoized dictation_jni readiness succeeded");
            }
            return promoted;
        } catch (Throwable failure) {
            logError("native readiness check failed", failure);
            return stockResult;
        }
    }

    public static void afterInitialVoiceSettings(Context context, Object controller) {
        if (!GboardAdvancedVoice1803RuntimeSettings.isEnabled()
                || context == null
                || controller == null) {
            return;
        }
        try {
            Handles handles = handles(controller.getClass().getClassLoader());
            Object currentState = handles.ngaStateProviderMethod.invoke(null);
            Object initialState = handles.ngaInitialStateField.get(null);
            if (maybeRestoreInitialVoiceSettings(
                    true,
                    null,
                    currentState,
                    initialState,
                    context,
                    controller,
                    handles.stockPreferenceFactoryMethod,
                    handles.stockPreferenceReadBooleanMethod,
                    handles.preferenceAvailabilityMethod,
                    handles.preferenceCheckedMethod)) {
                logInfo("restored official rows while NgaState=INITIAL");
            }
        } catch (Throwable failure) {
            logError("INITIAL voice settings restore failed", failure);
        }
    }

    public static void afterMddProviderConstructed(Object provider) {
        if (provider == null) {
            return;
        }
        if (GboardAdvancedVoice1803RuntimeSettings.isBengaliInterventionEnabled()) {
            requestBengaliMdd(provider);
        }
        if (!GboardAdvancedVoice1803RuntimeSettings
                .isZhTwPunctuationInterventionEnabled()) {
            return;
        }
        try {
            Handles handles = handles(provider.getClass().getClassLoader());
            if (maybeRequestExactZhTwMdd(
                    true,
                    null,
                    ZH_TW_MDD_REQUESTED,
                    provider,
                    handles.mddScopeField,
                    handles.mddRequestDownloadConstructor,
                    handles.mddCoroutineLaunchMethod)) {
                logInfo("requested stock zh-TW MDD provisioning group=mdd.zh");
            }
        } catch (Throwable failure) {
            logError("zh-TW MDD request failed", failure);
        }
    }

    private static void requestBengaliMdd(Object provider) {
        for (String languageTag : GboardAdvancedVoice1803Policy.BENGALI_LANGUAGE_TAGS) {
            AtomicBoolean guard = BENGALI_MDD_REQUESTED.get(languageTag);
            if (guard == null) {
                AtomicBoolean created = new AtomicBoolean(false);
                guard = BENGALI_MDD_REQUESTED.putIfAbsent(languageTag, created);
                if (guard == null) {
                    guard = created;
                }
            }
            try {
                Handles handles = handles(provider.getClass().getClassLoader());
                if (maybeRequestLocaleMdd(
                        true,
                        null,
                        guard,
                        provider,
                        Locale.forLanguageTag(languageTag),
                        handles.mddScopeField,
                        handles.mddRequestDownloadConstructor,
                        handles.mddCoroutineLaunchMethod)) {
                    logInfo("requested stock MDD provisioning locale=" + languageTag);
                }
            } catch (Throwable failure) {
                logError(languageTag + " MDD request failed", failure);
            }
        }
    }

    /**
     * Reconciles Gboard 18.0.3's install-time feature-split check with a Morphe fused APK.
     * The readiness bit is promoted only when the stock NativeLibHelper can really load the
     * dictation JNI library that was merged into the base APK.
     */
    public static boolean after1803NativeSplitReadiness(boolean stockResult) {
        if (stockResult || !GboardAdvancedVoice1803RuntimeSettings.isEnabled()) {
            return stockResult;
        }
        try {
            Handles handles = handles(runtimeClassLoader());
            boolean nativeLoaded = readMemoizedNativeReadiness(
                    handles.nativeReadinessSupplierField,
                    handles.nativeReadinessSupplierMethod);
            boolean promoted = GboardAdvancedVoice1803Policy.shouldPromoteNativeReadiness(
                    Boolean.valueOf(stockResult), nativeLoaded);
            if (promoted) {
                logInfo("promoted scn#a() after dictation_jni load succeeded");
            }
            return promoted;
        } catch (Throwable failure) {
            logError("18.0.3 native split readiness check failed", failure);
            return stockResult;
        }
    }

    /**
     * Admits opt-in locales into the stock supported-locale set. The ABI name is kept from
     * the original zh-TW-only hook; it now also admits Bengali when that setting is on.
     */
    public static Object includeExactZhTwSupportedLocale(Object stockLocales) {
        if (!(stockLocales instanceof Set<?>)) {
            return stockLocales;
        }
        Set<String> extraTags = enabledExtraLanguageTags();
        if (extraTags.isEmpty()) {
            return stockLocales;
        }
        Set<?> stock = (Set<?>) stockLocales;
        LinkedHashSet<Object> expanded = null;
        for (String languageTag : extraTags) {
            Locale locale = Locale.forLanguageTag(languageTag);
            if (stock.contains(locale)) {
                continue;
            }
            if (expanded == null) {
                expanded = new LinkedHashSet<Object>(stock);
            }
            expanded.add(locale);
        }
        return expanded == null ? stockLocales : expanded;
    }

    private static Set<String> enabledExtraLanguageTags() {
        LinkedHashSet<String> tags = new LinkedHashSet<String>();
        if (GboardAdvancedVoice1803RuntimeSettings.isZhTwPunctuationInterventionEnabled()) {
            tags.addAll(GboardAdvancedVoice1803Policy.ZH_TW_LANGUAGE_TAGS);
        }
        if (GboardAdvancedVoice1803RuntimeSettings.isBengaliInterventionEnabled()) {
            tags.addAll(GboardAdvancedVoice1803Policy.BENGALI_LANGUAGE_TAGS);
        }
        return tags;
    }

    public static boolean beforeFormatterConstructed(
            Locale locale,
            Object orationContext,
            boolean formatterDisabled) {
        if (!formatterDisabled) {
            return formatterDisabled;
        }
        Set<String> allowedTags = enabledExtraLanguageTags();
        if (allowedTags.isEmpty()) {
            return formatterDisabled;
        }
        try {
            Handles handles = handles(orationContext == null
                    ? runtimeClassLoader()
                    : orationContext.getClass().getClassLoader());
            Object[] args = new Object[] {
                    locale, orationContext, null, null, Boolean.valueOf(formatterDisabled)
            };
            if (maybeEnableFormatterForLanguageTags(
                    args,
                    allowedTags,
                    handles.orationConfigurationField,
                    handles.defaultConfigurationField,
                    handles.disableAdvancedFeaturesField)) {
                logInfo("enabled stock formatter locale="
                        + (locale == null ? "null" : locale.toLanguageTag()));
            }
            return ((Boolean) args[4]).booleanValue();
        } catch (Throwable failure) {
            logError("formatter gate failed", failure);
            return formatterDisabled;
        }
    }

    static String readFlagName(Object receiver) throws ReflectiveOperationException {
        if (receiver == null) {
            return null;
        }
        Field nameField = resolveFlagNameField(receiver.getClass());
        return nameField == null ? null : (String) nameField.get(receiver);
    }

    private static Field resolveFlagNameField(Class<?> receiverClass)
            throws NoSuchFieldException {
        Field cached = FLAG_NAME_FIELDS.get(receiverClass);
        if (cached != null) {
            return cached;
        }
        Field resolved = receiverClass.getDeclaredField("a");
        if (resolved.getType() != String.class) {
            return null;
        }
        resolved.setAccessible(true);
        Field existing = FLAG_NAME_FIELDS.putIfAbsent(receiverClass, resolved);
        return existing != null ? existing : resolved;
    }

    static boolean readMemoizedNativeReadiness(
            Field nativeReadinessSupplierField,
            Method nativeReadinessSupplierMethod)
            throws ReflectiveOperationException {
        if (nativeReadinessSupplierField == null
                || nativeReadinessSupplierMethod == null) {
            return false;
        }
        Object supplier = nativeReadinessSupplierField.get(null);
        if (supplier == null) {
            return false;
        }
        Object result = nativeReadinessSupplierMethod.invoke(supplier);
        return Boolean.TRUE.equals(result);
    }

    static boolean maybeRequestExactZhTwMdd(
            boolean enabled,
            Throwable constructorFailure,
            AtomicBoolean requestGuard,
            Object dataProvider,
            Field scopeField,
            Constructor<?> requestDownloadConstructor,
            Method coroutineLaunchMethod) throws Throwable {
        return maybeRequestLocaleMdd(
                enabled,
                constructorFailure,
                requestGuard,
                dataProvider,
                Locale.forLanguageTag("zh-TW"),
                scopeField,
                requestDownloadConstructor,
                coroutineLaunchMethod);
    }

    static boolean maybeRequestLocaleMdd(
            boolean enabled,
            Throwable constructorFailure,
            AtomicBoolean requestGuard,
            Object dataProvider,
            Locale locale,
            Field scopeField,
            Constructor<?> requestDownloadConstructor,
            Method coroutineLaunchMethod) throws Throwable {
        if (!enabled
                || constructorFailure != null
                || requestGuard == null
                || dataProvider == null
                || locale == null
                || scopeField == null
                || requestDownloadConstructor == null
                || coroutineLaunchMethod == null
                || !requestGuard.compareAndSet(false, true)) {
            return false;
        }
        try {
            Object scope = scopeField.get(dataProvider);
            if (scope == null) {
                throw new IllegalStateException("qzh.d MDD scope is null");
            }
            Object request = requestDownloadConstructor.newInstance(
                    dataProvider,
                    locale,
                    null);
            Object future = coroutineLaunchMethod.invoke(
                    null,
                    scope,
                    null,
                    request,
                    3);
            if (future == null) {
                throw new IllegalStateException(
                        "aavi.aq returned null for "
                                + locale.toLanguageTag() + " MDD request");
            }
            return true;
        } catch (Throwable throwable) {
            requestGuard.set(false);
            throw throwable;
        }
    }

    static boolean maybeEnableExactZhTwFormatter(
            Object[] args,
            Field orationConfigurationField,
            Field defaultConfigurationField,
            Field disableAdvancedFeaturesField)
            throws ReflectiveOperationException {
        return maybeEnableFormatterForLanguageTags(
                args,
                GboardAdvancedVoice1803Policy.ZH_TW_LANGUAGE_TAGS,
                orationConfigurationField,
                defaultConfigurationField,
                disableAdvancedFeaturesField);
    }

    static boolean maybeEnableFormatterForLanguageTags(
            Object[] args,
            Set<String> allowedLanguageTags,
            Field orationConfigurationField,
            Field defaultConfigurationField,
            Field disableAdvancedFeaturesField)
            throws ReflectiveOperationException {
        if (args == null
                || args.length <= 4
                || !(args[0] instanceof Locale)
                || args[1] == null
                || orationConfigurationField == null
                || defaultConfigurationField == null
                || disableAdvancedFeaturesField == null) {
            return false;
        }
        Locale locale = (Locale) args[0];
        Object configurationData = orationConfigurationField.get(args[1]);
        if (configurationData == null) {
            configurationData = defaultConfigurationField.get(null);
        }
        if (configurationData == null) {
            return false;
        }
        boolean stockAdvancedFeaturesDisabled =
                disableAdvancedFeaturesField.getBoolean(configurationData);
        Object originalFormatterDisabled = args[4];
        Object enforcedFormatterDisabled =
                GboardAdvancedVoice1803Policy.maybeEnableFormatterForLanguageTags(
                        locale,
                        allowedLanguageTags,
                        stockAdvancedFeaturesDisabled,
                        originalFormatterDisabled);
        if (!Boolean.TRUE.equals(originalFormatterDisabled)
                || !Boolean.FALSE.equals(enforcedFormatterDisabled)) {
            return false;
        }
        args[4] = Boolean.FALSE;
        return true;
    }

    static boolean maybeRestoreInitialVoiceSettings(
            boolean enabled,
            Throwable initializerFailure,
            Object currentState,
            Object initialState,
            Object context,
            Object preferenceController,
            Method preferenceFactoryMethod,
            Method preferenceReadBooleanMethod,
            Method preferenceAvailabilityMethod,
            Method preferenceCheckedMethod) throws ReflectiveOperationException {
        if (!enabled
                || initializerFailure != null
                || currentState == null
                || currentState != initialState
                || context == null
                || preferenceController == null
                || preferenceFactoryMethod == null
                || preferenceReadBooleanMethod == null
                || preferenceAvailabilityMethod == null
                || preferenceCheckedMethod == null) {
            return false;
        }
        Object preferences = preferenceFactoryMethod.invoke(null, context);
        if (preferences == null) {
            return false;
        }
        Object advancedVoiceValue = preferenceReadBooleanMethod.invoke(
                preferences,
                ADVANCED_VOICE_PREFERENCE_KEY,
                true);
        Object autoPunctuationValue = preferenceReadBooleanMethod.invoke(
                preferences,
                AUTO_PUNCTUATION_PREFERENCE_KEY,
                true);
        if (!(advancedVoiceValue instanceof Boolean)
                || !(autoPunctuationValue instanceof Boolean)) {
            return false;
        }
        try {
            preferenceCheckedMethod.invoke(
                    preferenceController,
                    ADVANCED_VOICE_PREFERENCE_KEY,
                    advancedVoiceValue);
            preferenceCheckedMethod.invoke(
                    preferenceController,
                    AUTO_PUNCTUATION_PREFERENCE_KEY,
                    autoPunctuationValue);
            preferenceAvailabilityMethod.invoke(
                    preferenceController,
                    ADVANCED_VOICE_PREFERENCE_KEY,
                    true);
            preferenceAvailabilityMethod.invoke(
                    preferenceController,
                    AUTO_PUNCTUATION_PREFERENCE_KEY,
                    true);
            return true;
        } catch (Throwable failure) {
            rollbackInitialVoiceSettings(
                    preferenceController,
                    preferenceAvailabilityMethod,
                    preferenceCheckedMethod);
            logError("INITIAL voice settings apply failed", failure);
            return false;
        }
    }

    private static void rollbackInitialVoiceSettings(
            Object preferenceController,
            Method preferenceAvailabilityMethod,
            Method preferenceCheckedMethod) {
        invokePreferenceBooleanSafely(
                preferenceAvailabilityMethod,
                preferenceController,
                ADVANCED_VOICE_PREFERENCE_KEY,
                false);
        invokePreferenceBooleanSafely(
                preferenceAvailabilityMethod,
                preferenceController,
                AUTO_PUNCTUATION_PREFERENCE_KEY,
                false);
        invokePreferenceBooleanSafely(
                preferenceCheckedMethod,
                preferenceController,
                ADVANCED_VOICE_PREFERENCE_KEY,
                false);
        invokePreferenceBooleanSafely(
                preferenceCheckedMethod,
                preferenceController,
                AUTO_PUNCTUATION_PREFERENCE_KEY,
                false);
    }

    private static void invokePreferenceBooleanSafely(
            Method method,
            Object receiver,
            int preferenceKey,
            boolean value) {
        try {
            method.invoke(receiver, preferenceKey, value);
        } catch (Throwable ignored) {
            // Rollback must never affect Gboard's settings initialization path.
        }
    }

    private static Handles handles(ClassLoader classLoader) throws Exception {
        ClassLoader resolvedLoader = classLoader != null ? classLoader : runtimeClassLoader();
        synchronized (HANDLES_BY_LOADER) {
            WeakReference<Handles> reference = HANDLES_BY_LOADER.get(resolvedLoader);
            Handles cached = reference == null ? null : reference.get();
            if (cached != null) {
                return cached;
            }
            Handles created = new Handles(resolvedLoader);
            HANDLES_BY_LOADER.put(resolvedLoader, new WeakReference<Handles>(created));
            return created;
        }
    }

    private static ClassLoader runtimeClassLoader() {
        ClassLoader classLoader = GboardAdvancedVoice1803Runtime.class.getClassLoader();
        if (classLoader == null) {
            throw new IllegalStateException("Advanced Voice runtime has no ClassLoader");
        }
        return classLoader;
    }

    private static void logInfo(String message) {
        try {
            if (INFO_LOG_COUNT.getAndIncrement() >= 30) {
                return;
            }
            Log.i(TAG, LOG_PREFIX + message);
        } catch (Throwable ignored) {
            // Logging must not affect Gboard.
        }
    }

    private static void logError(String message, Throwable throwable) {
        try {
            if (ERROR_LOG_COUNT.getAndIncrement() >= 8) {
                return;
            }
            Log.w(TAG, LOG_PREFIX + message, throwable);
        } catch (Throwable ignored) {
            // Logging must not affect Gboard.
        }
    }

    private static final class Handles {
        final Field nativeReadinessSupplierField;
        final Method nativeReadinessSupplierMethod;
        final Field orationConfigurationField;
        final Field defaultConfigurationField;
        final Field disableAdvancedFeaturesField;
        final Field mddScopeField;
        final Constructor<?> mddRequestDownloadConstructor;
        final Method mddCoroutineLaunchMethod;
        final Method ngaStateProviderMethod;
        final Field ngaInitialStateField;
        final Method stockPreferenceFactoryMethod;
        final Method stockPreferenceReadBooleanMethod;
        final Method preferenceAvailabilityMethod;
        final Method preferenceCheckedMethod;

        Handles(ClassLoader classLoader) throws Exception {
            nativeReadinessSupplierField = resolve(classLoader, "scn").getDeclaredField("b");
            nativeReadinessSupplierMethod = resolve(classLoader, "vpu").getDeclaredMethod("iM");

            Class<?> orationContextClass = resolve(classLoader, "enl");
            Class<?> configurationDataClass = resolve(classLoader, "enf");
            orationConfigurationField = orationContextClass.getDeclaredField("c");
            requireAssignable(
                    configurationDataClass,
                    orationConfigurationField.getType(),
                    "enl.c is not enf configuration data");
            defaultConfigurationField = configurationDataClass.getDeclaredField("a");
            if (!java.lang.reflect.Modifier.isStatic(defaultConfigurationField.getModifiers())
                    || defaultConfigurationField.getType() != configurationDataClass) {
                throw new NoSuchFieldException("enf.a is not the static enf default");
            }
            disableAdvancedFeaturesField = configurationDataClass.getDeclaredField("H");
            if (disableAdvancedFeaturesField.getType() != boolean.class) {
                throw new NoSuchFieldException("enf.H is not boolean");
            }

            ngaStateProviderMethod = null;
            ngaInitialStateField = null;
            stockPreferenceFactoryMethod = null;
            stockPreferenceReadBooleanMethod = null;
            preferenceAvailabilityMethod = null;
            preferenceCheckedMethod = null;

            Class<?> mddProviderClass = resolve(classLoader, "rtu");
            Class<?> mddScopeClass = resolve(classLoader, "absf");
            mddScopeField = mddProviderClass.getDeclaredField("d");
            requireAssignable(
                    mddScopeClass,
                    mddScopeField.getType(),
                    "rtu.d is not an absf scope");
            Class<?> mddRequestClass = resolve(classLoader, "rtt");
            Class<?> continuationClass = resolve(classLoader, "ablu");
            mddRequestDownloadConstructor = mddRequestClass.getDeclaredConstructor(
                    mddProviderClass,
                    Locale.class,
                    continuationClass);
            mddCoroutineLaunchMethod = resolve(classLoader, "absj").getDeclaredMethod(
                    "L",
                    mddScopeClass,
                    resolve(classLoader, "absg"),
                    resolve(classLoader, "abnt"),
                    int.class);

            setAccessible(
                    nativeReadinessSupplierField,
                    nativeReadinessSupplierMethod,
                    orationConfigurationField,
                    defaultConfigurationField,
                    disableAdvancedFeaturesField,
                    mddScopeField,
                    mddRequestDownloadConstructor,
                    mddCoroutineLaunchMethod,
                    ngaStateProviderMethod,
                    ngaInitialStateField,
                    stockPreferenceFactoryMethod,
                    stockPreferenceReadBooleanMethod,
                    preferenceAvailabilityMethod,
                    preferenceCheckedMethod);
        }

        private static Class<?> resolve(ClassLoader classLoader, String name)
                throws ClassNotFoundException {
            return Class.forName(name, false, classLoader);
        }

        private static void requireAssignable(
                Class<?> expected,
                Class<?> actual,
                String message) throws NoSuchFieldException {
            if (!expected.isAssignableFrom(actual)) {
                throw new NoSuchFieldException(message);
            }
        }

        private static void setAccessible(Object... members) {
            for (Object member : members) {
                if (member instanceof Field) {
                    ((Field) member).setAccessible(true);
                } else if (member instanceof Method) {
                    ((Method) member).setAccessible(true);
                } else if (member instanceof Constructor<?>) {
                    ((Constructor<?>) member).setAccessible(true);
                }
            }
        }
    }
}

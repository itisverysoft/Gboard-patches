package dev.jason.gboardpatches.extension.macbridge;

import android.content.Context;
import android.content.res.Resources;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The draggable "Send to Mac" and "Dictate to Mac" Access Points, on the shared
 * 18.0.3 catalog/controller hooks.
 */
public final class GboardMacBridgeAccessPoint1803Contribution {
    public static final GboardMacBridgeAccessPoint1803Contribution INSTANCE =
            new GboardMacBridgeAccessPoint1803Contribution();
    static final Entry SEND = new Entry("mac_bridge_send", "Send to Mac",
            "gboard_patches_mac_bridge_send");
    static final Entry DICTATE = new Entry("mac_bridge_dictate", "Dictate to Mac",
            "gboard_patches_mac_bridge_dictate");
    private static final Entry[] ENTRIES = {SEND, DICTATE};
    /** A stock 18.0.3 drawable, used to find the resource package and as a fallback icon. */
    static final int STOCK_DRAWABLE_ID = 0x7f08048b;

    private static volatile Handles handles;

    private GboardMacBridgeAccessPoint1803Contribution() {
    }

    public Object extendOrderCatalog(Context context, Object original) {
        try {
            if (!GboardMacBridgeRuntime.isToolbarAvailable(context)
                    || !(original instanceof Collection<?> collection)) {
                return original;
            }
            List<String> values = copyStrings(collection);
            for (Entry entry : ENTRIES) {
                if (!values.contains(entry.token)) {
                    values.add(entry.token);
                }
            }
            Class<?> immutableCollection = Class.forName(
                    "vxe", false, original.getClass().getClassLoader());
            Method copy = immutableCollection.getDeclaredMethod("n", Collection.class);
            copy.setAccessible(true);
            return copy.invoke(null, values);
        } catch (Throwable ignored) {
            return original;
        }
    }

    public void register(Object controller, Context context) {
        try {
            if (controller == null || context == null
                    || !GboardMacBridgeRuntime.isToolbarAvailable(context)) {
                return;
            }
            Context application = context.getApplicationContext();
            Context safeContext = application != null ? application : context;
            Handles active = handles(controller.getClass().getClassLoader());
            for (Entry entry : ENTRIES) {
                try {
                    Object builder = active.descriptorBuilderFactory.invoke(null);
                    active.builderTokenMethod.invoke(builder, entry.token);
                    active.builderIconResourceMethod.invoke(builder, entry.icon(context));
                    active.builderLabelTextField.set(builder, entry.label);
                    active.builderContentDescriptionTextField.set(builder, entry.label);
                    active.builderRunnableMethod.invoke(builder, new Action(entry, safeContext));
                    Object descriptor = active.builderBuildMethod.invoke(builder);
                    active.controllerRegisterMethod.invoke(controller, descriptor, false);
                } catch (Throwable ignored) {
                    // One Access Point failing must not take the other down.
                }
            }
        } catch (Throwable ignored) {
            // A synthetic Access Point must fail closed.
        }
    }

    static List<String> copyStrings(Collection<?> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (Object value : values) {
                if (value instanceof String stringValue && !result.contains(stringValue)) {
                    result.add(stringValue);
                }
            }
        }
        return result;
    }

    private static Handles handles(ClassLoader classLoader) throws Throwable {
        Handles current = handles;
        if (current != null && current.classLoader == classLoader) {
            return current;
        }
        synchronized (GboardMacBridgeAccessPoint1803Contribution.class) {
            current = handles;
            if (current == null || current.classLoader != classLoader) {
                current = Handles.resolve(classLoader);
                handles = current;
            }
            return current;
        }
    }

    static final class Entry {
        final String token;
        final String label;
        /** Drawable added by the Mac Bridge resource patch. */
        final String iconName;
        private volatile int iconId;

        Entry(String token, String label, String iconName) {
            this.token = token;
            this.label = label;
            this.iconName = iconName;
        }

        int icon(Context context) {
            int cached = iconId;
            if (cached != 0) {
                return cached;
            }
            int resolved = STOCK_DRAWABLE_ID;
            try {
                Resources resources = context.getResources();
                String resourcePackage = resources.getResourcePackageName(STOCK_DRAWABLE_ID);
                int patched = resources.getIdentifier(iconName, "drawable", resourcePackage);
                if (patched != 0) {
                    resolved = patched;
                }
            } catch (Throwable ignored) {
                // Keep the stock icon.
            }
            iconId = resolved;
            return resolved;
        }
    }

    private static final class Action implements Runnable {
        private final Entry entry;
        private final WeakReference<Context> contextReference;

        Action(Entry entry, Context context) {
            this.entry = entry;
            contextReference = new WeakReference<>(context);
        }

        @Override
        public void run() {
            try {
                if (entry == DICTATE) {
                    GboardMacBridgeRuntime.dictateFromKeyboard(contextReference.get());
                } else {
                    GboardMacBridgeRuntime.sendFromKeyboard(contextReference.get());
                }
            } catch (Throwable ignored) {
                // Access Point callbacks must not escape into Gboard.
            }
        }
    }

    private static final class Handles {
        final ClassLoader classLoader;
        final Method descriptorBuilderFactory;
        final Method builderTokenMethod;
        final Method builderRunnableMethod;
        final Method builderBuildMethod;
        final Method builderIconResourceMethod;
        final Field builderLabelTextField;
        final Field builderContentDescriptionTextField;
        final Method controllerRegisterMethod;

        Handles(ClassLoader classLoader, Method descriptorBuilderFactory,
                Method builderTokenMethod, Method builderRunnableMethod,
                Method builderBuildMethod, Method builderIconResourceMethod,
                Field builderLabelTextField, Field builderContentDescriptionTextField,
                Method controllerRegisterMethod) {
            this.classLoader = classLoader;
            this.descriptorBuilderFactory = descriptorBuilderFactory;
            this.builderTokenMethod = builderTokenMethod;
            this.builderRunnableMethod = builderRunnableMethod;
            this.builderBuildMethod = builderBuildMethod;
            this.builderIconResourceMethod = builderIconResourceMethod;
            this.builderLabelTextField = builderLabelTextField;
            this.builderContentDescriptionTextField = builderContentDescriptionTextField;
            this.controllerRegisterMethod = controllerRegisterMethod;
        }

        static Handles resolve(ClassLoader classLoader) throws Throwable {
            Class<?> descriptor = Class.forName("mic", false, classLoader);
            Class<?> builder = Class.forName("mhx", false, classLoader);
            Class<?> controller = Class.forName("mlh", false, classLoader);
            Method descriptorBuilderFactory = descriptor.getDeclaredMethod("c");
            Method builderTokenMethod = builder.getDeclaredMethod("l", String.class);
            Method builderRunnableMethod = builder.getDeclaredMethod("q", Runnable.class);
            Method builderBuildMethod = builder.getDeclaredMethod("a");
            Method builderIconResourceMethod = builder.getDeclaredMethod("i", int.class);
            Field builderLabelTextField = builder.getDeclaredField("d");
            Field builderContentDescriptionTextField = builder.getDeclaredField("e");
            Method controllerRegisterMethod = controller.getDeclaredMethod(
                    "g", descriptor, boolean.class);
            descriptorBuilderFactory.setAccessible(true);
            builderTokenMethod.setAccessible(true);
            builderRunnableMethod.setAccessible(true);
            builderBuildMethod.setAccessible(true);
            builderIconResourceMethod.setAccessible(true);
            builderLabelTextField.setAccessible(true);
            builderContentDescriptionTextField.setAccessible(true);
            controllerRegisterMethod.setAccessible(true);
            return new Handles(classLoader, descriptorBuilderFactory, builderTokenMethod,
                    builderRunnableMethod, builderBuildMethod, builderIconResourceMethod,
                    builderLabelTextField, builderContentDescriptionTextField,
                    controllerRegisterMethod);
        }
    }
}

package ca.dnamobile.dbridgelauncher.agent;

import java.io.ByteArrayInputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Flash-Droid-style grab guard for stock Axiom For Android while DroidBridge is
 * using a real/native hardware mouse.
 *
 * AFA can correctly ungrab its editor, but vanilla Minecraft may call
 * MouseHandler.grabMouse() again while Axiom's ImGui editor is active. On Android
 * Native Mouse that immediately re-enters relative/captured mode and recenters the
 * pointer. The editor releases it again, producing the visible center-flicker loop.
 *
 * This transformer injects a tiny guard at the head of Minecraft's grabMouse()
 * method. The guard is active only when:
 *   - the launcher detected stock axiom_android and enabled compatibility,
 *   - Native Mouse was selected (never Android Virtual Mouse), and
 *   - Axiom EditorUI.isActive() currently returns true.
 *
 * The patch is runtime-only. No Axiom/AFA jar is modified on disk.
 */
public final class AxiomMinecraftGrabGuard {
    public static final String POLICY = "native-mouse-editor-grab-guard-v5";

    private static final String MODE_PROPERTY = "droidbridge.axiom.android.compat";
    private static final String NATIVE_MOUSE_PROPERTY = "droidbridge.axiom.android.native_mouse";
    private static final String EDITOR_UI_CLASS = "com.moulberry.axiom.editor.EditorUI";

    private static volatile Class<?> editorUiClass;
    private static volatile Method editorActiveMethod;
    private static volatile boolean lookupFailedLogged;
    private static volatile boolean suppressing;
    private static volatile int suppressedCount;

    private AxiomMinecraftGrabGuard() {
    }

    public static boolean isArmed() {
        String backend = System.getProperty(MODE_PROPERTY, "");
        return backend != null
                && !backend.trim().isEmpty()
                && Boolean.parseBoolean(System.getProperty(NATIVE_MOUSE_PROPERTY, "false"));
    }

    /** Called from the injected head of Minecraft MouseHandler.grabMouse(). */
    public static boolean shouldCancelMinecraftGrab() {
        if (!isArmed()) return false;

        boolean active = isAxiomEditorActive();
        if (active) {
            int count = ++suppressedCount;
            if (!suppressing || count <= 3 || (count % 120) == 0) {
                System.out.println("DroidBridgeAxiomCompat: blocked Minecraft grabMouse while Axiom editor active"
                        + " policy=" + POLICY
                        + " nativeMouse=true"
                        + " count=" + count);
            }
            suppressing = true;
            return true;
        }

        if (suppressing) {
            suppressing = false;
            System.out.println("DroidBridgeAxiomCompat: Axiom editor inactive; Minecraft grabMouse restored"
                    + " policy=" + POLICY
                    + " suppressed=" + suppressedCount);
        }
        return false;
    }

    private static boolean isAxiomEditorActive() {
        try {
            Method method = editorActiveMethod;
            if (method == null) {
                Class<?> clazz = findLoadedEditorUiClass();
                if (clazz == null) return false;
                method = clazz.getDeclaredMethod("isActive");
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    // Public method on supported Axiom builds; access still works.
                }
                editorUiClass = clazz;
                editorActiveMethod = method;
                System.out.println("DroidBridgeAxiomCompat: EditorUI activity probe ready"
                        + " class=" + clazz.getName()
                        + " policy=" + POLICY);
            }
            Object value = method.invoke(null);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable throwable) {
            if (!lookupFailedLogged) {
                lookupFailedLogged = true;
                System.out.println("DroidBridgeAxiomCompat: EditorUI activity probe unavailable"
                        + " policy=" + POLICY
                        + " error=" + throwable);
            }
            return false;
        }
    }

    private static Class<?> findLoadedEditorUiClass() {
        Class<?> cached = editorUiClass;
        if (cached != null) return cached;

        Instrumentation instrumentation = StartInjectors.instrumentation();
        if (instrumentation == null) return null;
        try {
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            for (Class<?> clazz : loaded) {
                if (clazz != null && EDITOR_UI_CLASS.equals(clazz.getName())) {
                    return clazz;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /**
     * Patches grabMouse()/method_1612() using the Javassist copy already supplied
     * by DroidBridgeLibPatcher. Reflection keeps this small agent free of a bundled
     * Javassist/ASM copy and avoids duplicate bytecode libraries in Fabric/Knot.
     */
    public static byte[] patchMouseHandler(byte[] originalBytes) {
        if (!isArmed() || originalBytes == null || originalBytes.length < 16) return null;

        Object ctClass = null;
        try {
            ClassLoader loader = AxiomMinecraftGrabGuard.class.getClassLoader();
            Class<?> classPoolClass = Class.forName("javassist.ClassPool", true, loader);
            Class<?> ctClassClass = Class.forName("javassist.CtClass", true, loader);
            Class<?> ctMethodClass = Class.forName("javassist.CtMethod", true, loader);
            Class<?> classPathClass = Class.forName("javassist.ClassPath", true, loader);
            Class<?> classClassPathClass = Class.forName("javassist.ClassClassPath", true, loader);

            Constructor<?> poolCtor = classPoolClass.getConstructor(boolean.class);
            Object pool = poolCtor.newInstance(false);
            classPoolClass.getMethod("appendSystemPath").invoke(pool);

            Object helperPath = classClassPathClass.getConstructor(Class.class)
                    .newInstance(AxiomMinecraftGrabGuard.class);
            classPoolClass.getMethod("insertClassPath", classPathClass).invoke(pool, helperPath);

            Method makeClass = classPoolClass.getMethod("makeClass", java.io.InputStream.class);
            ctClass = makeClass.invoke(pool, new ByteArrayInputStream(originalBytes));

            Object methodsArray = ctClassClass.getMethod("getDeclaredMethods").invoke(ctClass);
            int length = Array.getLength(methodsArray);
            Method getName = ctMethodClass.getMethod("getName");
            Method getParameterTypes = ctMethodClass.getMethod("getParameterTypes");
            Method getReturnType = ctMethodClass.getMethod("getReturnType");
            Method insertBefore = ctMethodClass.getMethod("insertBefore", String.class);
            Method ctGetName = ctClassClass.getMethod("getName");

            int patched = 0;
            for (int i = 0; i < length; i++) {
                Object method = Array.get(methodsArray, i);
                String name = String.valueOf(getName.invoke(method));
                if (!"grabMouse".equals(name) && !"method_1612".equals(name)) continue;

                Object params = getParameterTypes.invoke(method);
                if (Array.getLength(params) != 0) continue;
                Object returnType = getReturnType.invoke(method);
                if (!"void".equals(String.valueOf(ctGetName.invoke(returnType)))) continue;

                insertBefore.invoke(method,
                        "{ if (" + AxiomMinecraftGrabGuard.class.getName()
                                + ".shouldCancelMinecraftGrab()) return; }");
                patched++;
            }

            if (patched == 0) {
                System.out.println("DroidBridgeAxiomCompat: unsupported MouseHandler layout"
                        + " policy=" + POLICY
                        + " reason=grabMouse/method_1612 not found action=no-patch");
                return null;
            }

            byte[] out = (byte[]) ctClassClass.getMethod("toBytecode").invoke(ctClass);
            System.out.println("DroidBridgeAxiomCompat: MouseHandler grab guard injected"
                    + " policy=" + POLICY
                    + " methods=" + patched);
            return out;
        } catch (Throwable throwable) {
            System.out.println("DroidBridgeAxiomCompat: MouseHandler grab guard transform exception"
                    + " policy=" + POLICY
                    + " error=" + throwable);
            throwable.printStackTrace(System.out);
            return null;
        } finally {
            if (ctClass != null) {
                try {
                    Class<?> ctClassClass = Class.forName("javassist.CtClass", false,
                            AxiomMinecraftGrabGuard.class.getClassLoader());
                    ctClassClass.getMethod("detach").invoke(ctClass);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

package ca.dnamobile.dbridgelauncher.agent;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Axiom-only Native Mouse authority.
 *
 * v8 stays classloader-safe like v7, but also fixes the actual overlay ownership
 * problem: Axiom's editor is not a Minecraft Screen, so Minecraft can remain
 * grabbed from gameplay while the editor is open.  The final Axiom frame hook
 * now actively releases Minecraft MouseHandler ownership while EditorUI is active.
 *
 * All runtime code is injected into the Axiom target class itself.  The transformed
 * game class never calls back into this javaagent, so Fabric/Knot does not need
 * visibility of methods_injector_agent.jar.
 */
public final class AxiomFinalImGuiInputGuard {
    public static final String POLICY = "axiom-imgui-native-mouse-authority-v8-editor-release";

    public static final String GLFW_TARGET = "com/moulberry/axiom/editor/CustomImGuiImplGlfw";
    public static final String SDL3_TARGET = "com/moulberry/axiom/editor/CustomImGuiWindowerSdl";

    private static final String MODE_PROPERTY = "droidbridge.axiom.android.compat";
    private static final String NATIVE_MOUSE_PROPERTY = "droidbridge.axiom.android.native_mouse";

    private AxiomFinalImGuiInputGuard() {}

    public static boolean isArmed() {
        String backend = System.getProperty(MODE_PROPERTY, "");
        return backend != null
                && !backend.trim().isEmpty()
                && Boolean.parseBoolean(System.getProperty(NATIVE_MOUSE_PROPERTY, "false"));
    }

    public static boolean isTarget(String className) {
        if (!isArmed() || className == null) return false;
        String mode = System.getProperty(MODE_PROPERTY, "");
        if ("glfw".equalsIgnoreCase(mode)) return GLFW_TARGET.equals(className);
        if ("sdl3".equalsIgnoreCase(mode) || "sdl".equalsIgnoreCase(mode)) return SDL3_TARGET.equals(className);
        return false;
    }

    public static byte[] patch(String className, byte[] originalBytes) {
        if (!isTarget(className) || originalBytes == null || originalBytes.length < 16) return null;

        Object ctClass = null;
        try {
            ClassLoader loader = AxiomFinalImGuiInputGuard.class.getClassLoader();
            Class<?> classPoolClass = Class.forName("javassist.ClassPool", true, loader);
            Class<?> ctClassClass = Class.forName("javassist.CtClass", true, loader);
            Class<?> ctMethodClass = Class.forName("javassist.CtMethod", true, loader);
            Class<?> ctFieldClass = Class.forName("javassist.CtField", true, loader);
            Class<?> ctNewMethodClass = Class.forName("javassist.CtNewMethod", true, loader);
            Class<?> classPathClass = Class.forName("javassist.ClassPath", true, loader);
            Class<?> classClassPathClass = Class.forName("javassist.ClassClassPath", true, loader);

            Constructor<?> poolCtor = classPoolClass.getConstructor(boolean.class);
            Object pool = poolCtor.newInstance(false);
            classPoolClass.getMethod("appendSystemPath").invoke(pool);
            Object helperPath = classClassPathClass.getConstructor(Class.class).newInstance(AxiomFinalImGuiInputGuard.class);
            classPoolClass.getMethod("insertClassPath", classPathClass).invoke(pool, helperPath);

            ctClass = classPoolClass.getMethod("makeClass", java.io.InputStream.class)
                    .invoke(pool, new ByteArrayInputStream(originalBytes));

            // Everything below is added to the Axiom target class itself.
            // Do not reference AxiomFinalImGuiInputGuard from injected runtime code.
            Method fieldMake = ctFieldClass.getMethod("make", String.class, ctClassClass);
            Method addField = ctClassClass.getMethod("addField", ctFieldClass);
            Method methodMake = ctNewMethodClass.getMethod("make", String.class, ctClassClass);
            Method addMethod = ctClassClass.getMethod("addMethod", ctMethodClass);

            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile java.lang.reflect.Method droidBridgeEditorActiveMethod;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile boolean droidBridgeEditorProbeReadyLogged;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile boolean droidBridgeEditorProbeFailedLogged;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static int droidBridgeEditorBlockCount;", ctClass));

            // Minecraft ownership probe/state.  All types referenced here are JDK types;
            // Minecraft/Axiom classes are discovered through the target's Knot loader.
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile java.lang.reflect.Method droidBridgeMinecraftGetInstanceMethod;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile java.lang.reflect.Field droidBridgeMinecraftMouseHandlerField;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile java.lang.reflect.Field droidBridgeMinecraftScreenField;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile java.lang.reflect.Method droidBridgeMinecraftReleaseMouseMethod;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile java.lang.reflect.Method droidBridgeMinecraftGrabMouseMethod;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile boolean droidBridgeMinecraftMouseProbeReadyLogged;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile boolean droidBridgeMinecraftMouseProbeFailedLogged;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static volatile boolean droidBridgeEditorMouseWasActive;", ctClass));
            addField.invoke(ctClass, fieldMake.invoke(null,
                    "private static int droidBridgeMinecraftReleaseCount;", ctClass));

            String activeProbe =
                    "private static boolean droidBridgeIsEditorActive() {"
                    + " try {"
                    + "  java.lang.reflect.Method m = droidBridgeEditorActiveMethod;"
                    + "  if (m == null) {"
                    + "   Class c = Class.forName(\"com.moulberry.axiom.editor.EditorUI\");"
                    + "   m = c.getDeclaredMethod(\"isActive\", new Class[0]);"
                    + "   try { m.setAccessible(true); } catch (Throwable ignored) {}"
                    + "   droidBridgeEditorActiveMethod = m;"
                    + "   if (!droidBridgeEditorProbeReadyLogged) {"
                    + "    droidBridgeEditorProbeReadyLogged = true;"
                    + "    System.out.println(\"DroidBridgeAxiomCompat: EditorUI activity probe ready policy=" + POLICY + " classloader=self\");"
                    + "   }"
                    + "  }"
                    + "  Object value = m.invoke(null, new Object[0]);"
                    + "  return java.lang.Boolean.TRUE.equals(value);"
                    + " } catch (Throwable t) {"
                    + "  if (!droidBridgeEditorProbeFailedLogged) {"
                    + "   droidBridgeEditorProbeFailedLogged = true;"
                    + "   System.out.println(\"DroidBridgeAxiomCompat: EditorUI activity probe unavailable policy=" + POLICY + " classloader=self error=\" + t);"
                    + "  }"
                    + "  return false;"
                    + " }"
                    + "}";
            addMethod.invoke(ctClass, methodMake.invoke(null, activeProbe, ctClass));

            String blockLogger =
                    "private static void droidBridgeNoteEditorGrabBlocked(String backend, String action) {"
                    + " int n = ++droidBridgeEditorBlockCount;"
                    + " if (n <= 8 || (n % 120) == 0) {"
                    + "  System.out.println(\"DroidBridgeAxiomCompat: blocked Axiom editor re-grab policy=" + POLICY
                    + " backend=\" + backend + \" action=\" + action + \" nativeMouse=true count=\" + n);"
                    + " }"
                    + "}";
            addMethod.invoke(ctClass, methodMake.invoke(null, blockLogger, ctClass));

            String mouseProbe =
                    "private static boolean droidBridgeEnsureMinecraftMouseProbe() {"
                    + " if (droidBridgeMinecraftGetInstanceMethod != null"
                    + "     && droidBridgeMinecraftMouseHandlerField != null"
                    + "     && droidBridgeMinecraftReleaseMouseMethod != null) return true;"
                    + " try {"
                    + "  Class mc = Class.forName(\"net.minecraft.client.Minecraft\");"
                    + "  java.lang.reflect.Method getInstance = null;"
                    + "  try { getInstance = mc.getDeclaredMethod(\"getInstance\", new Class[0]); }"
                    + "  catch (Throwable ignored) {"
                    + "   try { getInstance = mc.getDeclaredMethod(\"method_1551\", new Class[0]); } catch (Throwable ignored2) {}"
                    + "  }"
                    + "  if (getInstance == null) throw new java.lang.NoSuchMethodException(\"Minecraft.getInstance/method_1551\");"
                    + "  try { getInstance.setAccessible(true); } catch (Throwable ignored) {}"
                    + "  java.lang.reflect.Field mouseField = null;"
                    + "  java.lang.reflect.Field screenField = null;"
                    + "  java.lang.reflect.Field[] fields = mc.getDeclaredFields();"
                    + "  for (int i = 0; i < fields.length; i++) {"
                    + "   java.lang.reflect.Field f = fields[i];"
                    + "   String tn = f.getType().getName();"
                    + "   if (mouseField == null && \"net.minecraft.client.MouseHandler\".equals(tn)) mouseField = f;"
                    + "   if (screenField == null && \"net.minecraft.client.gui.screens.Screen\".equals(tn)) screenField = f;"
                    + "  }"
                    + "  if (mouseField == null) {"
                    + "   try { mouseField = mc.getDeclaredField(\"mouseHandler\"); } catch (Throwable ignored) {}"
                    + "  }"
                    + "  if (screenField == null) {"
                    + "   try { screenField = mc.getDeclaredField(\"screen\"); } catch (Throwable ignored) {}"
                    + "  }"
                    + "  if (mouseField == null) throw new java.lang.NoSuchFieldException(\"Minecraft.mouseHandler\");"
                    + "  try { mouseField.setAccessible(true); } catch (Throwable ignored) {}"
                    + "  if (screenField != null) { try { screenField.setAccessible(true); } catch (Throwable ignored) {} }"
                    + "  Class mh = mouseField.getType();"
                    + "  java.lang.reflect.Method release = null;"
                    + "  java.lang.reflect.Method grab = null;"
                    + "  try { release = mh.getDeclaredMethod(\"releaseMouse\", new Class[0]); }"
                    + "  catch (Throwable ignored) {"
                    + "   try { release = mh.getDeclaredMethod(\"method_1613\", new Class[0]); } catch (Throwable ignored2) {}"
                    + "  }"
                    + "  try { grab = mh.getDeclaredMethod(\"grabMouse\", new Class[0]); }"
                    + "  catch (Throwable ignored) {"
                    + "   try { grab = mh.getDeclaredMethod(\"method_1612\", new Class[0]); } catch (Throwable ignored2) {}"
                    + "  }"
                    + "  if (release == null) throw new java.lang.NoSuchMethodException(\"MouseHandler.releaseMouse/method_1613\");"
                    + "  try { release.setAccessible(true); } catch (Throwable ignored) {}"
                    + "  if (grab != null) { try { grab.setAccessible(true); } catch (Throwable ignored) {} }"
                    + "  droidBridgeMinecraftGetInstanceMethod = getInstance;"
                    + "  droidBridgeMinecraftMouseHandlerField = mouseField;"
                    + "  droidBridgeMinecraftScreenField = screenField;"
                    + "  droidBridgeMinecraftReleaseMouseMethod = release;"
                    + "  droidBridgeMinecraftGrabMouseMethod = grab;"
                    + "  if (!droidBridgeMinecraftMouseProbeReadyLogged) {"
                    + "   droidBridgeMinecraftMouseProbeReadyLogged = true;"
                    + "   System.out.println(\"DroidBridgeAxiomCompat: Minecraft mouse ownership probe ready policy=" + POLICY
                    + " release=\" + release.getName() + \" grab=\" + (grab == null ? \"<none>\" : grab.getName()));"
                    + "  }"
                    + "  return true;"
                    + " } catch (Throwable t) {"
                    + "  if (!droidBridgeMinecraftMouseProbeFailedLogged) {"
                    + "   droidBridgeMinecraftMouseProbeFailedLogged = true;"
                    + "   System.out.println(\"DroidBridgeAxiomCompat: Minecraft mouse ownership probe unavailable policy=" + POLICY
                    + " error=\" + t);"
                    + "  }"
                    + "  return false;"
                    + " }"
                    + "}";
            addMethod.invoke(ctClass, methodMake.invoke(null, mouseProbe, ctClass));

            String releaseOwnership =
                    "private static void droidBridgeReleaseMinecraftMouseForEditor() {"
                    + " if (!droidBridgeEnsureMinecraftMouseProbe()) return;"
                    + " try {"
                    + "  Object mc = droidBridgeMinecraftGetInstanceMethod.invoke(null, new Object[0]);"
                    + "  if (mc == null) return;"
                    + "  Object mouse = droidBridgeMinecraftMouseHandlerField.get(mc);"
                    + "  if (mouse == null) return;"
                    + "  droidBridgeMinecraftReleaseMouseMethod.invoke(mouse, new Object[0]);"
                    + "  int n = ++droidBridgeMinecraftReleaseCount;"
                    + "  if (n <= 4 || (n % 120) == 0) {"
                    + "   System.out.println(\"DroidBridgeAxiomCompat: Axiom editor owns absolute mouse policy=" + POLICY
                    + " action=release-minecraft-mouse count=\" + n);"
                    + "  }"
                    + " } catch (Throwable t) {"
                    + "  if (!droidBridgeMinecraftMouseProbeFailedLogged) {"
                    + "   droidBridgeMinecraftMouseProbeFailedLogged = true;"
                    + "   System.out.println(\"DroidBridgeAxiomCompat: release Minecraft mouse failed policy=" + POLICY + " error=\" + t);"
                    + "  }"
                    + " }"
                    + "}";
            addMethod.invoke(ctClass, methodMake.invoke(null, releaseOwnership, ctClass));

            String restoreOwnership =
                    "private static void droidBridgeRestoreMinecraftMouseAfterEditor() {"
                    + " if (!droidBridgeEnsureMinecraftMouseProbe()) return;"
                    + " java.lang.reflect.Method grab = droidBridgeMinecraftGrabMouseMethod;"
                    + " if (grab == null) return;"
                    + " try {"
                    + "  Object mc = droidBridgeMinecraftGetInstanceMethod.invoke(null, new Object[0]);"
                    + "  if (mc == null) return;"
                    + "  java.lang.reflect.Field sf = droidBridgeMinecraftScreenField;"
                    + "  if (sf != null && sf.get(mc) != null) return;"
                    + "  Object mouse = droidBridgeMinecraftMouseHandlerField.get(mc);"
                    + "  if (mouse == null) return;"
                    + "  grab.invoke(mouse, new Object[0]);"
                    + "  System.out.println(\"DroidBridgeAxiomCompat: restored Minecraft gameplay mouse after Axiom policy=" + POLICY + "\");"
                    + " } catch (Throwable t) {"
                    + "  System.out.println(\"DroidBridgeAxiomCompat: restore Minecraft mouse skipped policy=" + POLICY + " error=\" + t);"
                    + " }"
                    + "}";
            addMethod.invoke(ctClass, methodMake.invoke(null, restoreOwnership, ctClass));

            String syncOwnership =
                    "private static void droidBridgeSyncMinecraftMouseOwnership() {"
                    + " boolean active = droidBridgeIsEditorActive();"
                    + " if (active) {"
                    + "  droidBridgeEditorMouseWasActive = true;"
                    + "  droidBridgeReleaseMinecraftMouseForEditor();"
                    + " } else if (droidBridgeEditorMouseWasActive) {"
                    + "  droidBridgeEditorMouseWasActive = false;"
                    + "  droidBridgeRestoreMinecraftMouseAfterEditor();"
                    + " }"
                    + "}";
            addMethod.invoke(ctClass, methodMake.invoke(null, syncOwnership, ctClass));

            Object methodsArray = ctClassClass.getMethod("getDeclaredMethods").invoke(ctClass);
            int length = Array.getLength(methodsArray);
            Method getName = ctMethodClass.getMethod("getName");
            Method insertBefore = ctMethodClass.getMethod("insertBefore", String.class);

            int cursorHandlersPatched = 0;
            int frameHooksPatched = 0;

            // Axiom's final ImGui frame runs while the editor overlay is active.  This is
            // the critical hook v5-v7 were missing: release the already-active Minecraft
            // gameplay grab rather than merely blocking a later re-grab attempt.
            for (int i = 0; i < length; i++) {
                Object method = Array.get(methodsArray, i);
                String name = String.valueOf(getName.invoke(method));
                if ("newFrame".equals(name)) {
                    insertBefore.invoke(method, "{ droidBridgeSyncMinecraftMouseOwnership(); }");
                    frameHooksPatched++;
                }
            }

            if (GLFW_TARGET.equals(className)) {
                for (int i = 0; i < length; i++) {
                    Object method = Array.get(methodsArray, i);
                    String name = String.valueOf(getName.invoke(method));
                    if (!name.contains("$axiom_android$afa$cursorMode")) continue;
                    insertBefore.invoke(method,
                            "{ if (droidBridgeIsEditorActive() && $2 == 208897 && $3 == 212995) {"
                                    + " $3 = 212993;"
                                    + " droidBridgeNoteEditorGrabBlocked(\"glfw\", \"CURSOR_DISABLED->NORMAL\");"
                                    + " } }");
                    cursorHandlersPatched++;
                }
            } else if (SDL3_TARGET.equals(className)) {
                for (int i = 0; i < length; i++) {
                    Object method = Array.get(methodsArray, i);
                    String name = String.valueOf(getName.invoke(method));
                    if (name.contains("$axiom_android$afa$relativeMode")) {
                        insertBefore.invoke(method,
                                "{ if (droidBridgeIsEditorActive() && $2) {"
                                        + " $2 = false;"
                                        + " droidBridgeNoteEditorGrabBlocked(\"sdl3\", \"RELATIVE_TRUE->FALSE\");"
                                        + " } }");
                        cursorHandlersPatched++;
                    } else if (name.contains("$axiom_android$afa$captureMouse")) {
                        insertBefore.invoke(method,
                                "{ if (droidBridgeIsEditorActive() && $1) {"
                                        + " $1 = false;"
                                        + " droidBridgeNoteEditorGrabBlocked(\"sdl3\", \"CAPTURE_TRUE->FALSE\");"
                                        + " } }");
                        cursorHandlersPatched++;
                    }
                }
            }

            if (cursorHandlersPatched == 0 || frameHooksPatched == 0) {
                System.out.println("DroidBridgeAxiomCompat: unsupported final Axiom ImGui layout"
                        + " policy=" + POLICY + " class=" + className
                        + " cursorHandlers=" + cursorHandlersPatched
                        + " frameHooks=" + frameHooksPatched
                        + " action=no-patch");
                return null;
            }

            byte[] out = (byte[]) ctClassClass.getMethod("toBytecode").invoke(ctClass);
            System.out.println("DroidBridgeAxiomCompat: final Axiom ImGui guard injected"
                    + " policy=" + POLICY + " class=" + className
                    + " cursorHandlers=" + cursorHandlersPatched
                    + " frameHooks=" + frameHooksPatched
                    + " ownership=release-existing-minecraft-grab"
                    + " runtimeDependency=none");
            return out;
        } catch (Throwable throwable) {
            System.out.println("DroidBridgeAxiomCompat: final Axiom ImGui guard transform exception"
                    + " policy=" + POLICY + " class=" + className + " error=" + throwable);
            throwable.printStackTrace(System.out);
            return null;
        } finally {
            if (ctClass != null) {
                try {
                    Class<?> ctClassClass = Class.forName("javassist.CtClass", false,
                            AxiomFinalImGuiInputGuard.class.getClassLoader());
                    ctClassClass.getMethod("detach").invoke(ctClass);
                } catch (Throwable ignored) {}
            }
        }
    }
}

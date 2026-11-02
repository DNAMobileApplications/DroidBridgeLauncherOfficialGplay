package ca.dnamobile.dbridgelauncher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class StartInjectors {
    // Keep these names as literals in the already-loaded agent entry class.  The
    // transformer is invoked for every class definition, including JDK internals.
    // Referencing AxiomAndroidInputCompat from that hot path before we know the
    // class is an AFA target can recursively load helper/JDK classes and trigger
    // ClassCircularityError during early Java 25 bootstrap.
    private static final String AXIOM_GLFW_TARGET = "dev/whaltermc/afa/mixin/GlfwWindowerMixin";
    private static final String AXIOM_SDL3_TARGET = "dev/whaltermc/afa/mixin/SdlWindowerMixin";
    private static final String AXIOM_GLFW_INPUT_TARGET = "dev/whaltermc/afa/input/MobileInput";
    private static final String AXIOM_SDL3_INPUT_TARGET = "dev/whaltermc/afa/input/SdlInput";
    private static final String VEIL_TARGET = "foundry/veil/impl/client/imgui/VeilImGuiImpl";
    private static final String MOUSE_HANDLER_TARGET = "net/minecraft/client/MouseHandler";
    private static final String AXIOM_FINAL_GLFW_TARGET = "com/moulberry/axiom/editor/CustomImGuiImplGlfw";
    private static final String AXIOM_FINAL_SDL3_TARGET = "com/moulberry/axiom/editor/CustomImGuiWindowerSdl";
    private static volatile Instrumentation instrumentationRef;

    private StartInjectors() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        instrumentationRef = instrumentation;
        System.out.println("DroidBridge Agent: premain started bootstrap=safe-target-gate-v2 axiomImGuiGuard=v8-editor-release");

        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer
            ) {
                // CRITICAL: reject all unrelated classes before touching either helper.
                // In particular, do not initialize AxiomAndroidInputCompat while the JVM
                // is defining ConcurrentHashMap / ClassLoader implementation classes.
                if (AXIOM_GLFW_TARGET.equals(className) || AXIOM_SDL3_TARGET.equals(className)
                        || AXIOM_GLFW_INPUT_TARGET.equals(className) || AXIOM_SDL3_INPUT_TARGET.equals(className)) {
                    try {
                        if (!AxiomAndroidInputCompat.isTarget(className)) {
                            return null;
                        }
                        byte[] patched = AxiomAndroidInputCompat.patch(className, classfileBuffer);
                        if (patched != null) {
                            System.out.println("DroidBridgeAxiomCompat: patched stock AFA in memory"
                                    + " version=" + AxiomAndroidInputCompat.version()
                                    + " backend=" + AxiomAndroidInputCompat.mode()
                                    + " class=" + className);
                            return patched;
                        }
                        return null;
                    } catch (Throwable throwable) {
                        System.out.println("DroidBridgeAxiomCompat: patch failed version="
                                + safeAxiomVersion()
                                + " backend=" + safeAxiomMode()
                                + " class=" + className + " error=" + throwable);
                        throwable.printStackTrace(System.out);
                        return null;
                    }
                }

                if (AXIOM_FINAL_GLFW_TARGET.equals(className) || AXIOM_FINAL_SDL3_TARGET.equals(className)) {
                    try {
                        if (!AxiomFinalImGuiInputGuard.isTarget(className)) {
                            return null;
                        }
                        byte[] patched = AxiomFinalImGuiInputGuard.patch(className, classfileBuffer);
                        if (patched != null) {
                            System.out.println("DroidBridgeAxiomCompat: patched final Axiom ImGui input"
                                    + " policy=" + AxiomFinalImGuiInputGuard.POLICY
                                    + " backend=" + safeAxiomMode()
                                    + " version=" + safeAxiomVersion()
                                    + " class=" + className);
                            return patched;
                        }
                        return null;
                    } catch (Throwable throwable) {
                        System.out.println("DroidBridgeAxiomCompat: final Axiom ImGui input patch failed"
                                + " policy=" + AxiomFinalImGuiInputGuard.POLICY
                                + " class=" + className
                                + " error=" + throwable);
                        throwable.printStackTrace(System.out);
                        return null;
                    }
                }

                if (!VEIL_TARGET.equals(className)) {
                    return null;
                }

                try {
                    byte[] patched = VeilImguiOverrideDisable.patch(classfileBuffer);
                    if (patched != null) {
                        System.out.println("DroidBridge Agent: patched "
                                + VEIL_TARGET
                                + "#setImGuiPath() for Android");
                        return patched;
                    }

                    System.out.println("DroidBridge Agent: target class found but setImGuiPath() was not patched");
                    return null;
                } catch (Throwable throwable) {
                    System.out.println("DroidBridge Agent: failed to patch VeilImGuiImpl: " + throwable);
                    throwable.printStackTrace(System.out);
                    return null;
                }
            }
        }, false);
    }

    // These are only used after an AFA target class has already matched. Keep the
    // failure logger from turning a patch exception into another bootstrap failure.
    private static String safeAxiomMode() {
        try {
            return AxiomAndroidInputCompat.mode();
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    private static String safeAxiomVersion() {
        try {
            return AxiomAndroidInputCompat.version();
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    static Instrumentation instrumentation() {
        return instrumentationRef;
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }
}

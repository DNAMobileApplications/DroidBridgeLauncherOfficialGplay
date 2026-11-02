package ca.dnamobile.droidbridgelauncher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class StartInjectors {
    private static final String VEIL_TARGET = "foundry/veil/impl/client/imgui/VeilImGuiImpl";
    private static volatile Instrumentation instrumentationRef;

    private StartInjectors() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        instrumentationRef = instrumentation;
        System.out.println("DroidBridge Agent: premain started bootstrap=safe-target-gate-v3 stockAfa=unmodified");

        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer
            ) {
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

    static Instrumentation instrumentation() {
        return instrumentationRef;
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }
}

package ca.dnamobile.dbridgelauncher.agent;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Runtime-only compatibility repair for the stock AFA (Axiom For Android) mod.
 *
 * AFA adapts Axiom's desktop Windower to Android, but its editor can still re-enter
 * a grabbed/relative cursor state while DroidBridge is routing a real hardware mouse
 * as absolute UI input. That produces a grab/ungrab/recenter loop in Dear ImGui.
 *
 * DroidBridge owns the Android pointer policy, so this transformer keeps AFA's own
 * Windower adapter absolute/ungrabbed while compatibility mode is active:
 *  - GLFW AFA: force GLFW_CURSOR_NORMAL and suppress cursor warps.
 *  - SDL3 AFA: force relative mode off, capture off, and suppress mouse warps.
 *
 * Minecraft's normal gameplay grab path is not patched. The user's AFA JAR is never
 * modified on disk; only the matching mixin class is repaired in memory.
 *
 * This deliberately uses a small class-file parser instead of ASM. Attached AFA
 * releases span Java 17 through Java 25. All edits preserve bytecode length and the
 * existing StackMapTable/Mixin metadata, avoiding a dependency on a particular ASM
 * class-file release.
 */
public final class AxiomAndroidInputCompat {
    public static final String GLFW_TARGET = "dev/whaltermc/afa/mixin/GlfwWindowerMixin";
    public static final String SDL3_TARGET = "dev/whaltermc/afa/mixin/SdlWindowerMixin";
    public static final String GLFW_INPUT_TARGET = "dev/whaltermc/afa/input/MobileInput";
    public static final String SDL3_INPUT_TARGET = "dev/whaltermc/afa/input/SdlInput";

    private static final String MODE_PROPERTY = "droidbridge.axiom.android.compat";
    private static final String VERSION_PROPERTY = "droidbridge.axiom.android.version";
    public static final String POLICY = "tracked-driver-authority-v4";

    private static final String OPERATION =
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

    private static final String GLFW_CURSOR_MODE = "afa$cursorMode";
    private static final String GLFW_CURSOR_MODE_DESC = "(JII" + OPERATION + ")V";
    private static final String GLFW_CURSOR_POS = "afa$cursorPos";
    private static final String GLFW_CURSOR_POS_DESC = "(JDD" + OPERATION + ")V";

    private static final String SDL_RELATIVE_MODE = "afa$relativeMode";
    private static final String SDL_RELATIVE_MODE_DESC = "(JZ" + OPERATION + ")Z";
    private static final String SDL_CAPTURE_MOUSE = "afa$captureMouse";
    private static final String SDL_CAPTURE_MOUSE_DESC = "(Z" + OPERATION + ")Z";
    private static final String SDL_WARP_MOUSE = "afa$warpMouse";
    private static final String SDL_WARP_MOUSE_DESC = "(JFF" + OPERATION + ")V";

    private static final int GLFW_CURSOR_NORMAL = 212993;

    private static final int OPCODE_NOP = 0x00;
    private static final int OPCODE_ICONST_0 = 0x03;
    private static final int OPCODE_LLOAD_1 = 0x1F;
    private static final int OPCODE_ILOAD_3 = 0x1D;
    private static final int OPCODE_ILOAD = 0x15;
    private static final int OPCODE_LDC = 0x12;
    private static final int OPCODE_INVOKESTATIC = 0xB8;
    private static final int OPCODE_IRETURN = 0xAC;
    private static final int OPCODE_RETURN = 0xB1;

    private static final String MOBILE_INPUT = "dev/whaltermc/afa/input/MobileInput";
    private static final String SDL_INPUT = "dev/whaltermc/afa/input/SdlInput";

    private AxiomAndroidInputCompat() {
    }

    public static boolean isTarget(String className) {
        String mode = mode();
        if ("glfw".equals(mode)) {
            return GLFW_TARGET.equals(className) || GLFW_INPUT_TARGET.equals(className);
        }
        if ("sdl3".equals(mode)) {
            return SDL3_TARGET.equals(className) || SDL3_INPUT_TARGET.equals(className);
        }
        return false;
    }

    public static String mode() {
        String value = System.getProperty(MODE_PROPERTY, "");
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String version() {
        String value = System.getProperty(VERSION_PROPERTY, "");
        return value == null || value.trim().isEmpty() ? "<unknown>" : value.trim();
    }

    public static byte[] patch(String className, byte[] originalBytes) {
        if (originalBytes == null || originalBytes.length < 16) return null;
        String activeMode = mode();
        if (GLFW_INPUT_TARGET.equals(className) && "glfw".equals(activeMode)) {
            return patchGlfwInput(originalBytes);
        }
        if (SDL3_INPUT_TARGET.equals(className) && "sdl3".equals(activeMode)) {
            return patchSdl3Input(originalBytes);
        }
        // Keep the old mixin-class repair for environments that really define the
        // mixin class. Fabric normally consumes mixin bytecode as a resource, so v4
        // no longer relies on these targets.
        if (GLFW_TARGET.equals(className) && "glfw".equals(activeMode)) {
            return patchGlfw(originalBytes);
        }
        if (SDL3_TARGET.equals(className) && "sdl3".equals(activeMode)) {
            return patchSdl3(originalBytes);
        }
        return null;
    }

    private static byte[] patchGlfwInput(byte[] original) {
        byte[] out = original.clone();
        try {
            ClassLayout layout = parseClass(out);
            MethodCode poll = findCode(out, layout, "pollDriverSync", "()V");
            if (poll == null) return unsupported("glfw", "MobileInput.pollDriverSync missing");

            int longValue = findMethodRef(layout, "java/lang/Long", "longValue", "()J");
            int intValue = findMethodRef(layout, "java/lang/Integer", "intValue", "()I");
            int setCursorMode = findMethodRef(layout, MOBILE_INPUT, "setCursorMode", "(JI)V");
            int trace = findMethodRef(layout, MOBILE_INPUT, "trace", "(Ljava/lang/String;)V");
            if (longValue <= 0 || intValue <= 0 || setCursorMode <= 0 || trace <= 0) {
                return unsupported("glfw", "MobileInput authority refs missing");
            }

            // Stock AFA pollDriverSync mismatch tail is:
            //   aload_1, aload_3, Integer.intValue, modeName, iload_2, modeName,
            //   invokedynamic log, trace, aload_1, Long.longValue, iload_2, syncCursorMode
            // Replace the whole mismatch action with:
            //   setCursorMode(window.longValue(), tracked.intValue())
            // This makes AFA's requested state authoritative instead of adopting a
            // vanilla/driver grab that occurred behind its map. No code length or
            // StackMapTable offsets change.
            int start = -1;
            int end = -1;
            for (int i = poll.codeStart; i + 28 <= poll.codeEnd; i++) {
                if (u1(out, i) == 0x2B // aload_1
                        && u1(out, i + 1) == 0x2D // aload_3
                        && u1(out, i + 2) == 0xB6 && u2(out, i + 3) == intValue
                        && u1(out, i + 17) == OPCODE_INVOKESTATIC && u2(out, i + 18) == trace
                        && u1(out, i + 20) == 0x2B
                        && u1(out, i + 21) == 0xB6 && u2(out, i + 22) == longValue) {
                    start = i;
                    end = i + 28;
                    break;
                }
            }
            if (start < 0) return unsupported("glfw", "MobileInput.pollDriverSync layout changed");

            int p = start;
            out[p++] = 0x2B; // aload_1
            out[p++] = (byte) 0xB6; putU2(out, p, longValue); p += 2;
            out[p++] = 0x2D; // aload_3
            out[p++] = (byte) 0xB6; putU2(out, p, intValue); p += 2;
            out[p++] = (byte) OPCODE_INVOKESTATIC; putU2(out, p, setCursorMode); p += 2;
            while (p < end) out[p++] = (byte) OPCODE_NOP;
            return out;
        } catch (Throwable throwable) {
            System.out.println("DroidBridgeAxiomCompat: AFA patch exception version=" + version()
                    + " backend=glfw policy=" + POLICY + " error=" + throwable);
            return null;
        }
    }

    private static byte[] patchSdl3Input(byte[] original) {
        byte[] out = original.clone();
        try {
            ClassLayout layout = parseClass(out);
            MethodCode poll = findCode(out, layout, "pollDriverSync", "()V");
            if (poll == null) return unsupported("sdl3", "SdlInput.pollDriverSync missing");

            int longValue = findMethodRef(layout, "java/lang/Long", "longValue", "()J");
            int boolValue = findMethodRef(layout, "java/lang/Boolean", "booleanValue", "()Z");
            int setRelativeMode = findMethodRef(layout, SDL_INPUT, "setRelativeMode", "(JZ)Z");
            int trace = findMethodRef(layout, SDL_INPUT, "trace", "(Ljava/lang/String;)V");
            if (longValue <= 0 || boolValue <= 0 || setRelativeMode <= 0 || trace <= 0) {
                return unsupported("sdl3", "SdlInput authority refs missing");
            }

            // Stock SDL3 AFA does the same thing for relative mode: on a mismatch it
            // logs then overwrites RELATIVE_MODES with the driver's value. Replace
            // that mismatch body with setRelativeMode(window, tracked).
            int start = -1;
            int end = -1;
            for (int i = poll.codeStart; i + 25 <= poll.codeEnd; i++) {
                if (u1(out, i) == 0x2B // aload_1
                        && u1(out, i + 1) == 0x2D // aload_3
                        && u1(out, i + 2) == 0x1C // iload_2
                        && u1(out, i + 8) == OPCODE_INVOKESTATIC && u2(out, i + 9) == trace) {
                    start = i;
                    end = i + 25;
                    break;
                }
            }
            if (start < 0) return unsupported("sdl3", "SdlInput.pollDriverSync layout changed");

            int p = start;
            out[p++] = 0x2B; // aload_1
            out[p++] = (byte) 0xB6; putU2(out, p, longValue); p += 2;
            out[p++] = 0x2D; // aload_3
            out[p++] = (byte) 0xB6; putU2(out, p, boolValue); p += 2;
            out[p++] = (byte) OPCODE_INVOKESTATIC; putU2(out, p, setRelativeMode); p += 2;
            out[p++] = 0x57; // pop boolean return
            while (p < end) out[p++] = (byte) OPCODE_NOP;
            return out;
        } catch (Throwable throwable) {
            System.out.println("DroidBridgeAxiomCompat: AFA patch exception version=" + version()
                    + " backend=sdl3 policy=" + POLICY + " error=" + throwable);
            return null;
        }
    }

    private static byte[] patchGlfw(byte[] original) {
        byte[] out = original.clone();
        try {
            ClassLayout layout = parseClass(out);
            MethodCode cursorMode = findCode(out, layout, GLFW_CURSOR_MODE, GLFW_CURSOR_MODE_DESC);
            MethodCode cursorPos = findCode(out, layout, GLFW_CURSOR_POS, GLFW_CURSOR_POS_DESC);
            if (cursorMode == null || cursorPos == null) {
                return unsupported("glfw", "required wrapper method missing");
            }

            int normalConstant = findIntegerConstant(layout, GLFW_CURSOR_NORMAL);
            int requestCursorMode = findMethodRef(layout, MOBILE_INPUT, "requestCursorMode", "(JI)V");
            int forceCursorMode = findMethodRef(layout, MOBILE_INPUT, "forceCursorMode", "(JI)V");
            if (normalConstant <= 0 || normalConstant > 0xFF
                    || requestCursorMode <= 0 || forceCursorMode <= 0) {
                return unsupported("glfw", "required constant/method reference missing");
            }

            // Known AFA wrapper normal path:
            //   lload_1
            //   iload 4
            //   invokestatic MobileInput.requestCursorMode(JI)V
            // Keep instruction widths identical while converting it to:
            //   lload_1
            //   ldc GLFW_CURSOR_NORMAL
            //   invokestatic MobileInput.forceCursorMode(JI)V
            int code = cursorMode.codeStart;
            if (cursorMode.codeLength < 13
                    || u1(out, code + 6) != OPCODE_LLOAD_1
                    || u1(out, code + 7) != OPCODE_ILOAD
                    || u1(out, code + 8) != 4
                    || u1(out, code + 9) != OPCODE_INVOKESTATIC
                    || u2(out, code + 10) != requestCursorMode) {
                return unsupported("glfw", "afa$cursorMode layout changed");
            }
            out[code + 7] = (byte) OPCODE_LDC;
            out[code + 8] = (byte) normalConstant;
            putU2(out, code + 10, forceCursorMode);

            if (!neutralizeCode(out, cursorPos)) {
                return unsupported("glfw", "afa$cursorPos layout changed");
            }

            return out;
        } catch (Throwable throwable) {
            System.out.println("DroidBridgeAxiomCompat: AFA patch exception version=" + version()
                    + " backend=glfw policy=" + POLICY + " error=" + throwable);
            return null;
        }
    }

    private static byte[] patchSdl3(byte[] original) {
        byte[] out = original.clone();
        try {
            ClassLayout layout = parseClass(out);
            MethodCode relativeMode = findCode(out, layout, SDL_RELATIVE_MODE, SDL_RELATIVE_MODE_DESC);
            MethodCode captureMouse = findCode(out, layout, SDL_CAPTURE_MOUSE, SDL_CAPTURE_MOUSE_DESC);
            MethodCode warpMouse = findCode(out, layout, SDL_WARP_MOUSE, SDL_WARP_MOUSE_DESC);
            if (relativeMode == null || captureMouse == null || warpMouse == null) {
                return unsupported("sdl3", "required wrapper method missing");
            }

            int requestRelative = findMethodRef(layout, SDL_INPUT, "requestRelativeMode", "(JZ)Z");
            int forceRelative = findMethodRef(layout, SDL_INPUT, "forceRelativeMode", "(JZ)Z");
            int syncRelative = findMethodRef(layout, SDL_INPUT, "syncRelativeMode", "(JZ)V");
            int setCapture = findMethodRef(layout, SDL_INPUT, "setCapture", "(Z)Z");
            if (requestRelative <= 0 || forceRelative <= 0 || syncRelative <= 0 || setCapture <= 0) {
                return unsupported("sdl3", "required method reference missing");
            }

            // Normal path: lload_1, iload_3, requestRelativeMode ->
            //              lload_1, iconst_0, forceRelativeMode.
            // Fallback path is also forced false so an exception cannot restore
            // relative mode behind DroidBridge's absolute editor path.
            int rel = relativeMode.codeStart;
            if (relativeMode.codeLength < 17
                    || u1(out, rel) != OPCODE_LLOAD_1
                    || u1(out, rel + 1) != OPCODE_ILOAD_3
                    || u1(out, rel + 2) != OPCODE_INVOKESTATIC
                    || u2(out, rel + 3) != requestRelative
                    || u1(out, rel + 5) != OPCODE_IRETURN
                    || u1(out, rel + 8) != OPCODE_LLOAD_1
                    || u1(out, rel + 9) != OPCODE_ILOAD_3
                    || u1(out, rel + 10) != OPCODE_INVOKESTATIC
                    || u2(out, rel + 11) != syncRelative) {
                return unsupported("sdl3", "afa$relativeMode layout changed");
            }
            out[rel + 1] = (byte) OPCODE_ICONST_0;
            putU2(out, rel + 3, forceRelative);
            out[rel + 9] = (byte) OPCODE_ICONST_0;

            // setCapture(requested) -> setCapture(false). This wrapper is Axiom's
            // Windower adapter, not Minecraft's global SDL capture function.
            int capture = captureMouse.codeStart;
            if (captureMouse.codeLength < 5
                    || u1(out, capture) != 0x1B // iload_1
                    || u1(out, capture + 1) != OPCODE_INVOKESTATIC
                    || u2(out, capture + 2) != setCapture
                    || u1(out, capture + 4) != OPCODE_IRETURN) {
                return unsupported("sdl3", "afa$captureMouse layout changed");
            }
            out[capture] = (byte) OPCODE_ICONST_0;

            if (!neutralizeCode(out, warpMouse)) {
                return unsupported("sdl3", "afa$warpMouse layout changed");
            }

            return out;
        } catch (Throwable throwable) {
            System.out.println("DroidBridgeAxiomCompat: AFA patch exception version=" + version()
                    + " backend=sdl3 policy=" + POLICY + " error=" + throwable);
            return null;
        }
    }

    private static byte[] unsupported(String backend, String reason) {
        System.out.println("DroidBridgeAxiomCompat: unsupported AFA class layout version="
                + version() + " backend=" + backend + " policy=" + POLICY
                + " reason=" + reason + " action=no-patch");
        return null;
    }

    private static ClassLayout parseClass(byte[] bytes) {
        if (u4(bytes, 0) != 0xCAFEBABEL) {
            throw new IllegalArgumentException("not a class file");
        }

        int cpCount = u2(bytes, 8);
        String[] utf8 = new String[cpCount];
        int[] tags = new int[cpCount];
        int[] a = new int[cpCount];
        int[] b = new int[cpCount];
        int[] ints = new int[cpCount];

        int p = 10;
        for (int i = 1; i < cpCount; i++) {
            int tag = u1(bytes, p++);
            tags[i] = tag;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    int len = u2(bytes, p);
                    p += 2;
                    if (p + len > bytes.length) throw new IllegalArgumentException("utf8");
                    utf8[i] = new String(bytes, p, len, StandardCharsets.UTF_8);
                    p += len;
                    break;
                }
                case 3: // Integer
                    ints[i] = (int) u4(bytes, p);
                    p += 4;
                    break;
                case 4: // Float
                    p += 4;
                    break;
                case 5: // Long
                case 6: // Double
                    p += 8;
                    i++;
                    break;
                case 7:  // Class
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    a[i] = u2(bytes, p);
                    p += 2;
                    break;
                case 9:  // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    a[i] = u2(bytes, p);
                    b[i] = u2(bytes, p + 2);
                    p += 4;
                    break;
                case 15: // MethodHandle
                    a[i] = u1(bytes, p);
                    b[i] = u2(bytes, p + 1);
                    p += 3;
                    break;
                default:
                    throw new IllegalArgumentException("constant pool tag " + tag);
            }
            if (p > bytes.length) throw new IllegalArgumentException("constant pool overflow");
        }

        int afterCp = p;
        return new ClassLayout(utf8, tags, a, b, ints, afterCp);
    }

    private static MethodCode findCode(byte[] bytes, ClassLayout layout, String methodName, String methodDesc) {
        int p = layout.afterConstantPool;
        p += 6; // access_flags, this_class, super_class
        int interfaces = u2(bytes, p);
        p += 2 + interfaces * 2;

        int fields = u2(bytes, p);
        p += 2;
        for (int i = 0; i < fields; i++) p = skipMember(bytes, p);

        int methods = u2(bytes, p);
        p += 2;
        for (int i = 0; i < methods; i++) {
            if (p + 8 > bytes.length) throw new IllegalArgumentException("method");
            int nameIndex = u2(bytes, p + 2);
            int descIndex = u2(bytes, p + 4);
            int attrCount = u2(bytes, p + 6);
            p += 8;

            boolean target = methodName.equals(cp(layout.utf8, nameIndex))
                    && methodDesc.equals(cp(layout.utf8, descIndex));

            for (int attr = 0; attr < attrCount; attr++) {
                if (p + 6 > bytes.length) throw new IllegalArgumentException("method attribute");
                int attrName = u2(bytes, p);
                long lengthLong = u4(bytes, p + 2);
                if (lengthLong > Integer.MAX_VALUE) throw new IllegalArgumentException("attribute length");
                int length = (int) lengthLong;
                int data = p + 6;
                int next = data + length;
                if (next < data || next > bytes.length) throw new IllegalArgumentException("attribute overflow");

                if (target && "Code".equals(cp(layout.utf8, attrName))) {
                    if (length < 12 || data + 8 > bytes.length) return null;
                    long codeLengthLong = u4(bytes, data + 4);
                    if (codeLengthLong <= 0 || codeLengthLong > Integer.MAX_VALUE) return null;
                    int codeLength = (int) codeLengthLong;
                    int codeStart = data + 8;
                    int codeEnd = codeStart + codeLength;
                    if (codeEnd + 2 > bytes.length) return null;
                    return new MethodCode(codeStart, codeLength, codeEnd);
                }
                p = next;
            }
        }
        return null;
    }

    private static int findIntegerConstant(ClassLayout layout, int value) {
        for (int i = 1; i < layout.tags.length; i++) {
            if (layout.tags[i] == 3 && layout.ints[i] == value) return i;
        }
        return -1;
    }

    private static int findMethodRef(ClassLayout layout, String owner, String name, String desc) {
        for (int i = 1; i < layout.tags.length; i++) {
            if (layout.tags[i] != 10 && layout.tags[i] != 11) continue;
            int classIndex = layout.a[i];
            int natIndex = layout.b[i];
            if (classIndex <= 0 || classIndex >= layout.tags.length || layout.tags[classIndex] != 7) continue;
            if (natIndex <= 0 || natIndex >= layout.tags.length || layout.tags[natIndex] != 12) continue;

            String candidateOwner = cp(layout.utf8, layout.a[classIndex]);
            String candidateName = cp(layout.utf8, layout.a[natIndex]);
            String candidateDesc = cp(layout.utf8, layout.b[natIndex]);
            if (owner.equals(candidateOwner) && name.equals(candidateName) && desc.equals(candidateDesc)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean neutralizeCode(byte[] bytes, MethodCode method) {
        int codeStart = method.codeStart;
        int codeEnd = method.codeEnd;
        if (method.codeLength <= 0 || codeEnd + 2 > bytes.length) return false;

        int exceptionCount = u2(bytes, codeEnd);
        int exceptionTable = codeEnd + 2;
        if (exceptionTable + exceptionCount * 8 > bytes.length) return false;

        // Preserve exception-handler and StackMapTable offsets. AFA's known wrappers
        // put handlers at the end of the method. RETURN + NOP keeps all offsets stable.
        int firstHandler = method.codeLength;
        for (int i = 0; i < exceptionCount; i++) {
            int handlerPc = u2(bytes, exceptionTable + i * 8 + 4);
            if (handlerPc >= 0 && handlerPc < firstHandler) firstHandler = handlerPc;
        }
        if (firstHandler <= 0 || firstHandler > method.codeLength) firstHandler = method.codeLength;

        bytes[codeStart] = (byte) OPCODE_RETURN;
        for (int i = 1; i < firstHandler; i++) bytes[codeStart + i] = (byte) OPCODE_NOP;
        if (exceptionCount == 0 && method.codeLength > 1) bytes[codeEnd - 1] = (byte) OPCODE_RETURN;
        return true;
    }

    private static int skipMember(byte[] bytes, int p) {
        if (p + 8 > bytes.length) throw new IllegalArgumentException("member");
        int attrCount = u2(bytes, p + 6);
        p += 8;
        for (int i = 0; i < attrCount; i++) {
            if (p + 6 > bytes.length) throw new IllegalArgumentException("attribute");
            long length = u4(bytes, p + 2);
            long next = (long) p + 6L + length;
            if (next > bytes.length) throw new IllegalArgumentException("attribute length");
            p = (int) next;
        }
        return p;
    }

    private static String cp(String[] utf8, int index) {
        return index > 0 && index < utf8.length ? utf8[index] : null;
    }

    private static int u1(byte[] b, int p) {
        return b[p] & 0xFF;
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static long u4(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24)
                | ((long) (b[p + 1] & 0xFF) << 16)
                | ((long) (b[p + 2] & 0xFF) << 8)
                | (long) (b[p + 3] & 0xFF);
    }

    private static void putU2(byte[] b, int p, int value) {
        b[p] = (byte) ((value >>> 8) & 0xFF);
        b[p + 1] = (byte) (value & 0xFF);
    }

    private static final class ClassLayout {
        final String[] utf8;
        final int[] tags;
        final int[] a;
        final int[] b;
        final int[] ints;
        final int afterConstantPool;

        ClassLayout(String[] utf8, int[] tags, int[] a, int[] b, int[] ints, int afterConstantPool) {
            this.utf8 = utf8;
            this.tags = tags;
            this.a = a;
            this.b = b;
            this.ints = ints;
            this.afterConstantPool = afterConstantPool;
        }
    }

    private static final class MethodCode {
        final int codeStart;
        final int codeLength;
        final int codeEnd;

        MethodCode(int codeStart, int codeLength, int codeEnd) {
            this.codeStart = codeStart;
            this.codeLength = codeLength;
            this.codeEnd = codeEnd;
        }
    }
}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * Narrow pre-launch compatibility patch for VulkanMod 0.6.x on Android.
 */
package ca.dnamobile.droidbridgelauncher.modcompat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Repairs VulkanMod 0.6.x's stale per-swapchain sync-list cleanup without
 * changing the driver's requested present mode.
 *
 * <p>VulkanMod can recreate a swapchain whose image count differs from the
 * previous chain and then destroy the old sync objects using the new count.
 * On Turnip this was observed as index 5 from a five-element ArrayList. The
 * patch keeps VulkanMod's own control flow intact but replaces List.get(int)
 * calls inside Renderer.destroySyncObjects() with a tiny safe accessor. An
 * out-of-range cleanup lookup therefore produces VK_NULL_HANDLE (boxed 0L),
 * which Vulkan destroy calls can ignore, rather than aborting the render
 * thread before the replacement swapchain becomes usable.</p>
 *
 * <p>The bytecode substitution is deliberately stack-neutral and length-
 * neutral. invokevirtual is changed to invokestatic with the same effective
 * stack shape; invokeinterface's two trailing operand bytes become NOPs. No
 * branch offsets or StackMapTable frames move.</p>
 */
public final class VulkanModSyncObjectMitigation {
    private static final String RENDERER_ENTRY = "net/vulkanmod/vulkan/Renderer.class";
    private static final String HELPER_ENTRY = "net/vulkanmod/vulkan/DroidBridgeSyncSafety.class";
    private static final String MARKER_ENTRY = "META-INF/droidbridge-vulkanmod-syncfix-v1";
    private static final Pattern FABRIC_ID = Pattern.compile("\"id\"\\s*:\\s*\"vulkanmod\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FABRIC_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final String HELPER_B64 =
            "yv66vgAAADQAIgoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCwAIAAkHAAoMAAsADAEADmphdmEvdXRpbC9MaXN0AQAEc2l6ZQEAAygpSQoADgAPBwAQDAARABIBAA5qYXZhL2xhbmcvTG9uZwEAB3ZhbHVlT2YBABMoSilMamF2YS9sYW5nL0xvbmc7CwAIABQMABUAFgEAA2dldAEAFShJKUxqYXZhL2xhbmcvT2JqZWN0OwcAGAEAKm5ldC92dWxrYW5tb2QvdnVsa2FuL0Ryb2lkQnJpZGdlU3luY1NhZmV0eQEABENvZGUBAA9MaW5lTnVtYmVyVGFibGUBAAdzYWZlR2V0AQAlKExqYXZhL3V0aWwvTGlzdDtJKUxqYXZhL2xhbmcvT2JqZWN0OwEADVN0YWNrTWFwVGFibGUBAAlTaWduYXR1cmUBACgoTGphdmEvdXRpbC9MaXN0PCo+O0kpTGphdmEvbGFuZy9PYmplY3Q7AQAKU291cmNlRmlsZQEAGkRyb2lkQnJpZGdlU3luY1NhZmV0eS5qYXZhADEAFwACAAAAAAACAAIABQAGAAEAGQAAAB0AAQABAAAABSq3AAGxAAAAAQAaAAAABgABAAAABgAJABsAHAACABkAAABJAAIAAgAAAB8qxgARG5sADRsquQAHAQChAAgJuAANsCobuQATAgCwAAAAAgAaAAAADgADAAAACQASAAoAFwAMAB0AAAAEAAISBAAeAAAAAgAfAAEAIAAAAAIAIQ==";

    private VulkanModSyncObjectMitigation() {
    }

    public static String prepare(File gameDirectory) {
        if (gameDirectory == null) return null;
        File mods = new File(gameDirectory, "mods");
        File[] candidates = mods.listFiles((dir, name) -> name != null
                && name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (candidates == null || candidates.length == 0) return null;

        List<File> jars = new ArrayList<>();
        for (File candidate : candidates) jars.add(candidate);
        jars.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        for (File jar : jars) {
            try {
                Result result = patchJarIfNeeded(jar);
                if (result == null) continue;
                if (result.alreadyPatched) {
                    return "VulkanMod sync-object mitigation: already patched " + jar.getName();
                }
                if (result.patchedCalls > 0) {
                    return "VulkanMod sync-object mitigation: patched " + result.patchedCalls
                            + " stale sync-list access" + (result.patchedCalls == 1 ? "" : "es")
                            + " in " + jar.getName()
                            + (result.version == null ? "" : " (" + result.version + ")")
                            + "; VulkanMod present mode remains game-requested";
                }
                return "VulkanMod sync-object mitigation: structural signature mismatch; left "
                        + jar.getName() + " unchanged";
            } catch (Throwable throwable) {
                return "VulkanMod sync-object mitigation: failed " + jar.getName()
                        + " (" + throwable.getClass().getSimpleName() + ": "
                        + safeMessage(throwable) + ")";
            }
        }
        return null;
    }

    private static Result patchJarIfNeeded(File jar) throws IOException {
        byte[] renderer;
        String version;
        boolean alreadyPatched;
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry rendererEntry = zip.getEntry(RENDERER_ENTRY);
            if (rendererEntry == null) return null;

            String fabricJson = readUtf8(zip, zip.getEntry("fabric.mod.json"));
            boolean identified = fabricJson != null && FABRIC_ID.matcher(fabricJson).find();
            if (!identified && !jar.getName().toLowerCase(Locale.ROOT).contains("vulkanmod")) {
                return null;
            }

            version = parseVersion(fabricJson);
            if (!isTargetVersion(version, jar.getName())) {
                return null;
            }

            alreadyPatched = zip.getEntry(MARKER_ENTRY) != null;
            if (alreadyPatched) return new Result(version, 0, true);
            renderer = readAll(zip.getInputStream(rendererEntry));
        }

        PatchResult patch = patchRenderer(renderer);
        // The affected VulkanMod 0.6.x Renderer.destroySyncObjects() shape has
        // exactly three per-image sync-list reads. Requiring that exact count
        // keeps the version gate broad enough for 0.6.6/0.6.8 while refusing
        // to rewrite a future Renderer layout that merely shares the method name.
        if (patch.patchedCalls != 3) {
            return new Result(version, 0, false);
        }

        byte[] helper = Base64.getDecoder().decode(HELPER_B64);
        File temp = new File(jar.getParentFile(), jar.getName() + ".droidbridge-syncfix.tmp");
        if (temp.exists() && !temp.delete()) {
            throw new IOException("unable to remove stale temporary jar");
        }

        try {
            rewriteJar(jar, temp, patch.bytes, helper, version, patch.patchedCalls);
            replaceAtomically(temp, jar);
        } finally {
            if (temp.exists()) temp.delete();
        }
        return new Result(version, patch.patchedCalls, false);
    }

    private static boolean isTargetVersion(String version, String fileName) {
        if (version != null) {
            // 0.6.6 (MC 1.21.10) and 0.6.8 (MC 26.1.2) both exhibit the same
            // stale sync-list cleanup bug. The exact-three-access structural
            // guard below remains authoritative before any class is rewritten.
            return version.startsWith("0.6.");
        }
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.contains("vulkanmod") && lower.contains("0.6.");
    }

    private static String parseVersion(String json) {
        if (json == null) return null;
        Matcher matcher = FABRIC_VERSION.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void rewriteJar(
            File source,
            File temp,
            byte[] renderer,
            byte[] helper,
            String version,
            int patchedCalls
    ) throws IOException {
        try (ZipFile zip = new ZipFile(source);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (RENDERER_ENTRY.equals(name) || HELPER_ENTRY.equals(name) || MARKER_ENTRY.equals(name)) {
                    continue;
                }
                ZipEntry copy = copyMetadata(entry);
                out.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        copy(in, out);
                    }
                }
                out.closeEntry();
            }

            putBytes(out, RENDERER_ENTRY, renderer);
            putBytes(out, HELPER_ENTRY, helper);
            String marker = "DroidBridge VulkanMod sync-object mitigation v1\n"
                    + "version=" + (version == null ? "unknown" : version) + "\n"
                    + "patchedCalls=" + patchedCalls + "\n";
            putBytes(out, MARKER_ENTRY, marker.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static ZipEntry copyMetadata(ZipEntry original) {
        ZipEntry copy = new ZipEntry(original.getName());
        if (original.getTime() >= 0) copy.setTime(original.getTime());
        if (original.getComment() != null) copy.setComment(original.getComment());
        if (original.getExtra() != null) copy.setExtra(original.getExtra());
        if (original.getMethod() == ZipEntry.STORED) {
            copy.setMethod(ZipEntry.STORED);
            copy.setSize(original.getSize());
            copy.setCrc(original.getCrc());
        }
        return copy;
    }

    private static void putBytes(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static void replaceAtomically(File temp, File destination) throws IOException {
        try {
            Files.move(temp.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException ignored) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static PatchResult patchRenderer(byte[] original) throws IOException {
        ClassView originalView = ClassView.parse(original);
        int oldCount = originalView.cpCount;

        int helperUtf8 = oldCount;
        int helperClass = oldCount + 1;
        int methodNameUtf8 = oldCount + 2;
        int methodDescUtf8 = oldCount + 3;
        int nameAndType = oldCount + 4;
        int helperMethodRef = oldCount + 5;

        ByteArrayOutputStream cpAddBytes = new ByteArrayOutputStream();
        DataOutputStream cpAdd = new DataOutputStream(cpAddBytes);
        writeUtf8Cp(cpAdd, "net/vulkanmod/vulkan/DroidBridgeSyncSafety");
        cpAdd.writeByte(7); cpAdd.writeShort(helperUtf8);
        writeUtf8Cp(cpAdd, "safeGet");
        writeUtf8Cp(cpAdd, "(Ljava/util/List;I)Ljava/lang/Object;");
        cpAdd.writeByte(12); cpAdd.writeShort(methodNameUtf8); cpAdd.writeShort(methodDescUtf8);
        cpAdd.writeByte(10); cpAdd.writeShort(helperClass); cpAdd.writeShort(nameAndType);
        cpAdd.flush();

        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream(original.length + cpAddBytes.size());
        rebuilt.write(original, 0, 8);
        rebuilt.write(((oldCount + 6) >>> 8) & 0xff);
        rebuilt.write((oldCount + 6) & 0xff);
        rebuilt.write(original, 10, originalView.cpEnd - 10);
        rebuilt.write(cpAddBytes.toByteArray());
        rebuilt.write(original, originalView.cpEnd, original.length - originalView.cpEnd);
        byte[] bytes = rebuilt.toByteArray();

        ClassView view = ClassView.parse(bytes);
        int patchedCalls = view.patchDestroySyncObjects(bytes, helperMethodRef);
        return new PatchResult(bytes, patchedCalls);
    }

    private static void writeUtf8Cp(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1);
        out.writeShort(utf8.length);
        out.write(utf8);
    }

    private static String readUtf8(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[32768];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) out.write(buffer, 0, read);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "no message" : message;
    }

    private static int u1(byte[] b, int p) {
        return b[p] & 0xff;
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    private static int i4(byte[] b, int p) {
        return ((b[p] & 0xff) << 24)
                | ((b[p + 1] & 0xff) << 16)
                | ((b[p + 2] & 0xff) << 8)
                | (b[p + 3] & 0xff);
    }

    private static void putU2(byte[] b, int p, int value) {
        b[p] = (byte) (value >>> 8);
        b[p + 1] = (byte) value;
    }

    private static final class Result {
        final String version;
        final int patchedCalls;
        final boolean alreadyPatched;

        Result(String version, int patchedCalls, boolean alreadyPatched) {
            this.version = version;
            this.patchedCalls = patchedCalls;
            this.alreadyPatched = alreadyPatched;
        }
    }

    private static final class PatchResult {
        final byte[] bytes;
        final int patchedCalls;

        PatchResult(byte[] bytes, int patchedCalls) {
            this.bytes = bytes;
            this.patchedCalls = patchedCalls;
        }
    }

    private static final class CpEntry {
        int tag;
        int a;
        int b;
        String utf8;
    }

    private static final class ClassView {
        final int cpCount;
        final int cpEnd;
        final CpEntry[] cp;
        final byte[] bytes;

        private ClassView(int cpCount, int cpEnd, CpEntry[] cp, byte[] bytes) {
            this.cpCount = cpCount;
            this.cpEnd = cpEnd;
            this.cp = cp;
            this.bytes = bytes;
        }

        static ClassView parse(byte[] bytes) throws IOException {
            if (bytes.length < 10 || i4(bytes, 0) != 0xCAFEBABE) {
                throw new IOException("Renderer.class is not a valid class file");
            }
            int count = u2(bytes, 8);
            CpEntry[] cp = new CpEntry[count];
            int p = 10;
            for (int i = 1; i < count; i++) {
                if (p >= bytes.length) throw new IOException("truncated constant pool");
                int tag = u1(bytes, p++);
                CpEntry e = new CpEntry();
                e.tag = tag;
                cp[i] = e;
                switch (tag) {
                    case 1: {
                        int length = u2(bytes, p); p += 2;
                        if (p + length > bytes.length) throw new IOException("truncated UTF8 constant");
                        e.utf8 = new String(bytes, p, length, StandardCharsets.UTF_8);
                        p += length;
                        break;
                    }
                    case 3:
                    case 4:
                        p += 4;
                        break;
                    case 5:
                    case 6:
                        p += 8;
                        i++;
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        e.a = u2(bytes, p); p += 2;
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        e.a = u2(bytes, p);
                        e.b = u2(bytes, p + 2);
                        p += 4;
                        break;
                    case 15:
                        e.a = u1(bytes, p);
                        e.b = u2(bytes, p + 1);
                        p += 3;
                        break;
                    default:
                        throw new IOException("unsupported class constant-pool tag " + tag);
                }
                if (p > bytes.length) throw new IOException("truncated constant pool payload");
            }
            return new ClassView(count, p, cp, bytes);
        }

        int patchDestroySyncObjects(byte[] mutable, int helperMethodRef) throws IOException {
            int p = cpEnd;
            if (p + 8 > mutable.length) throw new IOException("truncated class header");
            p += 6; // access_flags, this_class, super_class
            int interfaces = u2(mutable, p); p += 2 + interfaces * 2;
            p = skipMembers(mutable, p); // fields

            int methods = u2(mutable, p); p += 2;
            int patched = 0;
            boolean found = false;
            for (int i = 0; i < methods; i++) {
                if (p + 8 > mutable.length) throw new IOException("truncated method_info");
                p += 2; // access flags
                int nameIndex = u2(mutable, p); p += 2;
                int descIndex = u2(mutable, p); p += 2;
                int attributes = u2(mutable, p); p += 2;
                String methodName = utf8(nameIndex);
                String descriptor = utf8(descIndex);
                boolean target = "destroySyncObjects".equals(methodName) && "()V".equals(descriptor);
                if (target) found = true;

                for (int a = 0; a < attributes; a++) {
                    if (p + 6 > mutable.length) throw new IOException("truncated method attribute");
                    int attrNameIndex = u2(mutable, p);
                    int attrLength = i4(mutable, p + 2);
                    int attrData = p + 6;
                    if (attrLength < 0 || attrData + attrLength > mutable.length) {
                        throw new IOException("invalid method attribute length");
                    }
                    if (target && "Code".equals(utf8(attrNameIndex))) {
                        if (attrLength < 12) throw new IOException("invalid Code attribute");
                        int codeLength = i4(mutable, attrData + 4);
                        int codeStart = attrData + 8;
                        if (codeLength < 0 || codeStart + codeLength > mutable.length) {
                            throw new IOException("invalid bytecode length");
                        }
                        patched += patchCode(mutable, codeStart, codeLength, helperMethodRef);
                    }
                    p = attrData + attrLength;
                }
            }
            if (!found) throw new IOException("destroySyncObjects()V not found");
            return patched;
        }

        private int skipMembers(byte[] b, int p) throws IOException {
            int count = u2(b, p); p += 2;
            for (int i = 0; i < count; i++) {
                if (p + 8 > b.length) throw new IOException("truncated field_info");
                p += 6;
                int attributes = u2(b, p); p += 2;
                for (int a = 0; a < attributes; a++) {
                    if (p + 6 > b.length) throw new IOException("truncated field attribute");
                    int length = i4(b, p + 2);
                    if (length < 0 || p + 6 + length > b.length) throw new IOException("invalid field attribute");
                    p += 6 + length;
                }
            }
            return p;
        }

        private int patchCode(byte[] b, int start, int length, int helperMethodRef) throws IOException {
            int patched = 0;
            int pc = 0;
            while (pc < length) {
                int pos = start + pc;
                int opcode = u1(b, pos);
                if (opcode == 0xb6 || opcode == 0xb9) { // invokevirtual / invokeinterface
                    int cpIndex = u2(b, pos + 1);
                    MethodRef ref = methodRef(cpIndex);
                    if (ref != null
                            && "get".equals(ref.name)
                            && "(I)Ljava/lang/Object;".equals(ref.desc)
                            && ("java/util/List".equals(ref.owner)
                                || "java/util/ArrayList".equals(ref.owner))) {
                        b[pos] = (byte) 0xb8; // invokestatic
                        putU2(b, pos + 1, helperMethodRef);
                        if (opcode == 0xb9) {
                            // invokeinterface is five bytes; keep every existing
                            // offset stable by consuming its two extra operand
                            // bytes as no-op instructions.
                            b[pos + 3] = 0x00;
                            b[pos + 4] = 0x00;
                        }
                        patched++;
                    }
                }
                int step = instructionLength(b, start, pc, length);
                if (step <= 0 || pc + step > length) {
                    throw new IOException("invalid bytecode at pc=" + pc + " opcode=0x"
                            + Integer.toHexString(opcode));
                }
                pc += step;
            }
            return patched;
        }

        private MethodRef methodRef(int index) {
            if (index <= 0 || index >= cp.length) return null;
            CpEntry ref = cp[index];
            if (ref == null || (ref.tag != 10 && ref.tag != 11)) return null;
            CpEntry ownerClass = entry(ref.a, 7);
            CpEntry nt = entry(ref.b, 12);
            if (ownerClass == null || nt == null) return null;
            String owner = utf8(ownerClass.a);
            String name = utf8(nt.a);
            String desc = utf8(nt.b);
            return owner == null || name == null || desc == null ? null : new MethodRef(owner, name, desc);
        }

        private CpEntry entry(int index, int tag) {
            if (index <= 0 || index >= cp.length) return null;
            CpEntry e = cp[index];
            return e != null && e.tag == tag ? e : null;
        }

        private String utf8(int index) {
            if (index <= 0 || index >= cp.length) return null;
            CpEntry e = cp[index];
            return e != null && e.tag == 1 ? e.utf8 : null;
        }

        private static int instructionLength(byte[] b, int codeStart, int pc, int codeLength) throws IOException {
            int opcode = u1(b, codeStart + pc);
            switch (opcode) {
                case 0x10: // bipush
                case 0x12: // ldc
                case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
                case 0x36: case 0x37: case 0x38: case 0x39: case 0x3a:
                case 0xa9: // ret
                case 0xbc: // newarray
                    return 2;
                case 0x11: // sipush
                case 0x13: case 0x14:
                case 0x84: // iinc
                case 0x99: case 0x9a: case 0x9b: case 0x9c: case 0x9d: case 0x9e:
                case 0x9f: case 0xa0: case 0xa1: case 0xa2: case 0xa3: case 0xa4:
                case 0xa5: case 0xa6: case 0xa7: case 0xa8:
                case 0xb2: case 0xb3: case 0xb4: case 0xb5:
                case 0xb6: case 0xb7: case 0xb8:
                case 0xbb: case 0xbd: case 0xc0: case 0xc1:
                case 0xc6: case 0xc7:
                    return 3;
                case 0xb9: case 0xba: case 0xc8: case 0xc9:
                    return 5;
                case 0xc5:
                    return 4;
                case 0xc4: { // wide
                    if (pc + 1 >= codeLength) throw new IOException("truncated wide opcode");
                    int modified = u1(b, codeStart + pc + 1);
                    return modified == 0x84 ? 6 : 4;
                }
                case 0xaa: { // tableswitch
                    int afterOpcode = pc + 1;
                    int padding = (4 - (afterOpcode & 3)) & 3;
                    int base = codeStart + afterOpcode + padding;
                    if (base + 12 > codeStart + codeLength) throw new IOException("truncated tableswitch");
                    int low = i4(b, base + 4);
                    int high = i4(b, base + 8);
                    long count = (long) high - (long) low + 1L;
                    if (count < 0 || count > 0x100000L) throw new IOException("invalid tableswitch span");
                    return 1 + padding + 12 + (int) count * 4;
                }
                case 0xab: { // lookupswitch
                    int afterOpcode = pc + 1;
                    int padding = (4 - (afterOpcode & 3)) & 3;
                    int base = codeStart + afterOpcode + padding;
                    if (base + 8 > codeStart + codeLength) throw new IOException("truncated lookupswitch");
                    int pairs = i4(b, base + 4);
                    if (pairs < 0 || pairs > 0x100000) throw new IOException("invalid lookupswitch pairs");
                    return 1 + padding + 8 + pairs * 8;
                }
                default:
                    return 1;
            }
        }
    }

    private static final class MethodRef {
        final String owner;
        final String name;
        final String desc;

        MethodRef(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }
    }
}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.runtime.Logger;

/**
 * Installs DroidBridge's runtime-only Legacy4J controller compatibility agent.
 *
 * The agent does not edit the Legacy4J jar. It only repairs the affected
 * SDLControllerHandler 1.9.1+ update gate while the game JVM loads the class.
 */
public final class Legacy4JControllerCompatAgentInstaller {
    private static final String ASSET_PATH =
            "components/legacy4j_controller_compat/legacy4j_controller_compat_agent.jar";
    private static final String OUTPUT_DIR = "legacy4j_controller_compat";
    private static final String OUTPUT_JAR = "legacy4j_controller_compat_agent.jar";

    private Legacy4JControllerCompatAgentInstaller() {
    }

    @Nullable
    public static File install(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), OUTPUT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log("Legacy4JControllerCompat: failed to create directory: " + dir.getAbsolutePath());
            return null;
        }

        File out = new File(dir, OUTPUT_JAR);
        File tmp = new File(dir, OUTPUT_JAR + ".tmp");

        try (InputStream input = context.getAssets().open(ASSET_PATH);
             FileOutputStream output = new FileOutputStream(tmp, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();

            if (out.exists() && !out.delete()) {
                throw new IOException("Unable to replace old compatibility agent: " + out.getAbsolutePath());
            }
            if (!tmp.renameTo(out)) {
                throw new IOException("Unable to move compatibility agent into place: " + out.getAbsolutePath());
            }
            if (!out.isFile() || out.length() <= 0) {
                throw new IOException("Installed compatibility agent is missing or empty");
            }

            log("Legacy4JControllerCompat: installed runtime agent " + out.getAbsolutePath());
            return out;
        } catch (Throwable throwable) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            log("Legacy4JControllerCompat: agent install failed: " + throwable);
            return null;
        }
    }

    public static boolean addJavaAgentArg(
            @NonNull Context context,
            @NonNull List<String> jvmArgs
    ) {
        File agent = install(context);
        if (agent == null) return false;

        String expected = "-javaagent:" + agent.getAbsolutePath();
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String value = jvmArgs.get(i);
            if (value != null && value.startsWith("-javaagent:") && value.contains(OUTPUT_JAR)) {
                jvmArgs.remove(i);
            }
        }

        // Put the tiny compatibility agent before the general DroidBridge patcher so its
        // target-class transformer is registered as early as possible.
        jvmArgs.add(0, expected);
        log("Legacy4JControllerCompat: enabled " + expected);
        return true;
    }

    private static void log(@NonNull String message) {
        try {
            Logger.appendToLog(message.endsWith("\n") ? message : message + "\n");
        } catch (Throwable ignored) {
            android.util.Log.i("Legacy4JControllerCompat", message);
        }
    }
}

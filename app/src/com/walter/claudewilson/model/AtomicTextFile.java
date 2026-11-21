package com.walter.claudewilson.model;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe UTF-8 file persistence via temp-file write, fsync, and atomic rename.
 *
 * <p>Direct truncation of {@code persona-state.json} or {@code region-personas.json} risks
 * leaving a zero-byte or half-written file on process kill or soft reboot ({@code ctl.restart
 * zygote}). Writing to a sibling {@code .tmp} file, syncing its descriptor, and atomically
 * replacing the destination keeps the previous valid snapshot intact until the new one is durable.
 */
public final class AtomicTextFile {

    private AtomicTextFile() {
    }

    /** Reads the entire UTF-8 file using a full loop, or returns null when absent/empty. */
    public static String readUtf8(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
        if (out.size() == 0) {
            return null;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Atomically writes UTF-8 bytes to {@code target} via sibling temp file + fsync + replace. */
    public static void writeUtf8(File target, byte[] bytes) throws IOException {
        if (target == null || bytes == null) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create parent directory: " + parent);
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        boolean replaced = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (tmp.renameTo(target)) {
                replaced = true;
            } else {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                replaced = true;
            }
        } finally {
            if (!replaced && tmp.exists()) {
                // Best-effort cleanup of orphan temp file after an unsuccessful write.
                tmp.delete();
            }
        }
    }

    /** Convenience overload encoding {@code text} as UTF-8 before atomic persistence. */
    public static void writeUtf8(File target, String text) throws IOException {
        writeUtf8(target, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }
}

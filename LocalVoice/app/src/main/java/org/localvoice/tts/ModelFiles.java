package org.localvoice.tts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Disk-only installation rules, also exercised without an Android device. */
final class ModelFiles {
    private ModelFiles() {}

    static File child(File root, String relative) throws IOException {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\") || relative.contains(":"))
            throw new IOException("Unsafe model path");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe model path");
        File file = new File(root, relative).getCanonicalFile();
        if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Model path escapes storage");
        return file;
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[65536];
                int n;
                while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static boolean verified(File file, long size, String hash) throws IOException {
        return file.isFile() && file.length() == size && sha256(file).equals(hash);
    }

    static void writeReceipt(File directory, String revision) throws IOException {
        try (FileOutputStream out = new FileOutputStream(child(directory, "installed.manifest"))) {
            out.write(revision.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    static boolean receiptMatches(File directory, String revision) {
        try {
            File receipt = child(directory, "installed.manifest");
            if (!receipt.isFile() || receipt.length() != revision.getBytes(StandardCharsets.UTF_8).length) return false;
            byte[] value = new byte[(int) receipt.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(receipt))) { in.readFully(value); }
            return revision.equals(new String(value, StandardCharsets.UTF_8));
        } catch (IOException e) { return false; }
    }

    static void activate(File staging, File installed) throws IOException {
        if (installed.exists() || !staging.renameTo(installed)) throw new IOException("Cannot activate model atomically");
    }

    static void remove(File root) throws IOException {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) {
            if (!child.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("Unsafe stored path");
            remove(child);
        }
        if (root.exists() && !root.delete()) throw new IOException("Cannot remove " + root.getName());
    }
}

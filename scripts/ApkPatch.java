// SPDX-License-Identifier: MIT
// Fast-path: replace entries (classesN.dex, assets/version.properties) inside an already built
// unsigned APK, copying all other entries untouched. Used by scripts/build_fast.bat|sh (F-00).

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ApkPatch {

    private ApkPatch() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || (args.length - 2) % 2 != 0) {
            System.err.println("usage: ApkPatch <base.apk> <out.apk> <entryName> <file> [<entryName> <file> ...]");
            System.exit(2);
        }
        Path base = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Map<String, Path> replacements = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i += 2) {
            Path file = Paths.get(args[i + 1]);
            if (!Files.isRegularFile(file)) {
                fail("replacement file not found: " + file);
            }
            if (Files.size(file) == 0) {
                fail("replacement file is empty: " + file);
            }
            replacements.put(args[i], file);
        }

        int entries = 0;
        int replaced = 0;
        long written = 0;
        try (ZipFile zip = new ZipFile(base.toFile())) {
            for (String name : replacements.keySet()) {
                if (zip.getEntry(name) == null) {
                    fail("entry " + name + " not found in " + base
                            + " (stale base APK? run a full build first)");
                }
            }
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(out.toFile())))) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry src = en.nextElement();
                    String name = src.getName();
                    Path replacement = replacements.get(name);
                    if (replacement != null) {
                        ZipEntry dst = new ZipEntry(name);
                        dst.setTime(src.getTime());
                        dst.setMethod(ZipEntry.DEFLATED);
                        zos.putNextEntry(dst);
                        try (InputStream in = Files.newInputStream(replacement)) {
                            copy(in, zos);
                        }
                        zos.closeEntry();
                        replaced++;
                        written += Files.size(replacement);
                        System.out.println("replaced " + name + " (" + Files.size(replacement) + " bytes)");
                    } else if (src.isDirectory()) {
                        ZipEntry dst = new ZipEntry(name);
                        dst.setTime(src.getTime());
                        zos.putNextEntry(dst);
                        zos.closeEntry();
                    } else if (src.getMethod() == ZipEntry.STORED) {
                        byte[] data;
                        try (InputStream in = zip.getInputStream(src)) {
                            data = readAll(in);
                        }
                        ZipEntry dst = new ZipEntry(name);
                        dst.setTime(src.getTime());
                        dst.setMethod(ZipEntry.STORED);
                        dst.setSize(data.length);
                        dst.setCompressedSize(data.length);
                        CRC32 crc = new CRC32();
                        crc.update(data);
                        dst.setCrc(crc.getValue());
                        zos.putNextEntry(dst);
                        zos.write(data);
                        zos.closeEntry();
                    } else {
                        ZipEntry dst = new ZipEntry(name);
                        dst.setTime(src.getTime());
                        dst.setMethod(ZipEntry.DEFLATED);
                        zos.putNextEntry(dst);
                        try (InputStream in = zip.getInputStream(src)) {
                            copy(in, zos);
                        }
                        zos.closeEntry();
                    }
                    entries++;
                }
            }
        } catch (Exception e) {
            fail(e.toString());
        }
        if (replaced != replacements.size()) {
            fail("replaced " + replaced + " of " + replacements.size() + " entries");
        }
        System.out.println("ApkPatch: " + entries + " entries, replaced " + replaced
                + ", payload " + written + " bytes -> " + out);
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }

    private static void fail(String message) {
        System.err.println("ApkPatch: " + message);
        System.exit(1);
    }
}

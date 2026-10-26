package top.niunaijun.blackbox.core.system.pm.installer;


import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;


public class CopyExecutor implements Executor {

    /**
     * Ensure copied APKs are byte-for-byte identical to the originals.
     * Length-only checks can overlook in-place corruption, which would
     * invalidate the APK signature and break genuine Play verification.
     */
    private static byte[] sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        // Extract native libraries from *every* APK, including ABI splits.
        String[] originalSplits = ps.pkg.applicationInfo.splitSourceDirs;
        try {
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                NativeUtils.copyNativeLib(new File(ps.pkg.baseCodePath), BEnvironment.getAppLibDir(ps.pkg.packageName));
                if (originalSplits != null) {
                    for (String split : originalSplits) {
                        NativeUtils.copyNativeLib(new File(split), BEnvironment.getAppLibDir(ps.pkg.packageName));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
        if (option.isFlag(InstallOption.FLAG_STORAGE)) {
            
            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(ps.pkg.packageName);
            try {
                long expectedBytes = origFile.length();
                if (expectedBytes <= 0) {
                    throw new IOException("Source APK is missing or empty.");
                }
                byte[] expectedDigest = sha256(origFile);
                if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                    boolean b = FileUtils.renameTo(origFile, newFile);
                    if (!b) {
                        FileUtils.copyFile(origFile, newFile);
                    }
                } else {
                    FileUtils.copyFile(origFile, newFile);
                }
                if (newFile.length() != expectedBytes ||
                        !MessageDigest.isEqual(expectedDigest, sha256(newFile)) ||
                        !newFile.setReadOnly()) {
                    throw new IOException("The copied base APK differs from the original.");
                }
                // Keep split code and resources in the same private app directory.
                // Never leave paths pointing to the host app after installation.
                if (originalSplits != null && originalSplits.length > 0) {
                    String[] copiedSplits = new String[originalSplits.length];
                    for (int i = 0; i < originalSplits.length; i++) {
                        File source = new File(originalSplits[i]);
                        String name = source.getName();
                        if (!source.isFile() || !name.matches("[A-Za-z0-9._-]+\\.apk")
                                || "base.apk".equals(name)) {
                            throw new IOException("Invalid split APK: " + name);
                        }
                        byte[] splitDigest = sha256(source);
                        File destination = new File(BEnvironment.getAppDir(ps.pkg.packageName), name);
                        FileUtils.copyFile(source, destination);
                        if (destination.length() != source.length() ||
                                !MessageDigest.isEqual(splitDigest, sha256(destination)) ||
                                !destination.setReadOnly()) {
                            throw new IOException("The copied split APK differs from its source: " + name);
                        }
                        copiedSplits[i] = destination.getAbsolutePath();
                    }
                    ps.pkg.applicationInfo.splitSourceDirs = copiedSplits;
                    ps.pkg.applicationInfo.splitPublicSourceDirs = copiedSplits.clone();
                }
                ps.pkg.baseCodePath = newFile.getAbsolutePath();
                ps.pkg.applicationInfo.sourceDir = newFile.getAbsolutePath();
                ps.pkg.applicationInfo.publicSourceDir = newFile.getAbsolutePath();
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        } else if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
            
        }
        return 0;
    }
}

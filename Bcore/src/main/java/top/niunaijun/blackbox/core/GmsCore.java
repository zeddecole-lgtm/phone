package top.niunaijun.blackbox.core;

import android.content.pm.PackageManager;

import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Best-effort installation of genuine Google packages from the host phone.
 *
 * This cannot make a virtual environment Play-certified and cannot satisfy
 * hardware-backed attestation or a third-party app's App Check policy.
 */
public class GmsCore {
    private static final Set<String> GOOGLE_APP = new HashSet<>();
    private static final Set<String> GOOGLE_SERVICE = new HashSet<>();

    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";
    public static final String GOOGLE_LOGIN_PKG = "com.google.android.gsf.login";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add(GOOGLE_LOGIN_PKG);
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String packageName) {
        return GOOGLE_APP.contains(packageName) || GOOGLE_SERVICE.contains(packageName);
    }

    private static boolean isPresentOnHost(String packageName) {
        try {
            BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static InstallResult installComponent(String packageName, int userId) {
        BlackBoxCore core = BlackBoxCore.get();
        if (core.isInstalled(packageName, userId)) {
            return new InstallResult();
        }
        if (!isPresentOnHost(packageName)) {
            return new InstallResult().installError(packageName + " is not installed on the phone.");
        }
        InstallResult installed = core.installPackageAsUser(packageName, userId);
        if (!installed.success) {
            return new InstallResult().installError(packageName + ": "
                    + (installed.msg == null ? "unknown installation failure" : installed.msg));
        }
        if (!core.isInstalled(packageName, userId)) {
            return new InstallResult().installError(packageName
                    + ": the package manager did not register the installed copy.");
        }
        return new InstallResult();
    }

    public static InstallResult installGApps(int userId) {
        if (!isSupportGms()) {
            return new InstallResult().installError(
                    "Google Play services is unavailable on this phone.");
        }

        // Google Services Framework, when present, should be installed first.
        // Some host ROMs ship Play services without a separately visible GSF.
        if (isPresentOnHost(GSF_PKG)) {
            InstallResult gsf = installComponent(GSF_PKG, userId);
            if (!gsf.success) return gsf;
        }

        // Some phone builds have a separate Google Account Login authenticator.
        // Where present, it is needed in addition to GSF and Play services.
        if (isPresentOnHost(GOOGLE_LOGIN_PKG)) {
            InstallResult login = installComponent(GOOGLE_LOGIN_PKG, userId);
            if (!login.success) return login;
        }

        // GMS is mandatory: never report success when only optional packages
        // were copied into the sandbox.
        InstallResult gms = installComponent(GMS_PKG, userId);
        if (!gms.success) return gms;

        // Play Store is optional for Google's background service availability.
        // It may require features the virtual runtime cannot provide.
        if (isPresentOnHost(VENDING_PKG)) {
            InstallResult store = installComponent(VENDING_PKG, userId);
            if (!store.success) return store;
        }

        if (!isInstalledGoogleService(userId)) {
            return new InstallResult().installError(
                    "Google Play services was not registered in the sandbox.");
        }
        return new InstallResult();
    }

    public static void uninstallGApps(int userId) {
        BlackBoxCore core = BlackBoxCore.get();
        for (String pkg : GOOGLE_SERVICE) {
            if (core.isInstalled(pkg, userId)) core.uninstallPackageAsUser(pkg, userId);
        }
        for (String pkg : GOOGLE_APP) {
            if (core.isInstalled(pkg, userId)) core.uninstallPackageAsUser(pkg, userId);
        }
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }

    public static boolean isSupportGms() {
        return isPresentOnHost(GMS_PKG);
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}

// SPDX-License-Identifier: MIT
package lenovotouch;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Applies BMA253 tap-threshold sensitivity over /dev/i2c-0 (0x18).
 *
 * <p>Tries access paths in order:
 * <ol>
 *   <li>Direct in-process JNI ({@code libbma253.so}) — works with zero overhead if /dev/i2c-0 is writable.</li>
 *   <li>Magisk/root {@code su} invoking {@link Bma253Cli} via {@code app_process}.</li>
 * </ol>
 * If neither can open /dev/i2c-0, tap detection via SparrowService still runs at stock sensitivity.
 */
public final class Bma253Tuner {
    private static final int EACCES = 13;
    private static final int EPERM = 1;
    private static final long HELPER_TIMEOUT_MS = 3000L;

    public static final String ROOT_SETUP_INSTRUCTIONS =
        "Changing sensitivity requires root (SU) enabled for Kiosk Satellite:\n"
        + "1. KS Settings > App Launcher > Enable App Launcher\n"
        + "2. Edit Apps > enable Magisk\n"
        + "3. Enable Return Automatically when untouched > set time to 30 seconds\n"
        + "4. KS slideout menu > Apps > Magisk > SU > + (add) > Kiosk Satellite > BACK (top right)\n"
        + "5. Wait 30 seconds to return to KS\n"
        + "6. Return \"Return Automatically\" and \"time\" settings to how you choose.";

    public static final class Result {
        public final boolean applied;
        public final boolean rootRequired;
        public final String method;
        public final String message;

        Result(boolean applied, boolean rootRequired, String method, String message) {
            this.applied = applied;
            this.rootRequired = rootRequired;
            this.method = method;
            this.message = message;
        }
    }

    private final PluginHost host;
    private volatile String libPath;
    private volatile String pkgPath;
    private volatile boolean nativeReady;
    private volatile String nativeError;

    public Bma253Tuner(PluginHost host) {
        this.host = host;
    }

    /** Load the bundled native JNI library once per session. Safe to call repeatedly. */
    public synchronized boolean ensureLoaded() {
        if (nativeReady) return true;
        try {
            libPath = host.nativeLibraryPath("bma253");
            pkgPath = host.packagePath();
            Bma253Native.load(libPath);
            nativeReady = true;
            nativeError = null;
            return true;
        } catch (Throwable t) {
            nativeError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return false;
        }
    }

    /** Apply the requested sensitivity level. Must be called off KS's main callback thread. */
    public Result apply(Bma253Sensitivity.Level level) {
        if (!ensureLoaded()) {
            return new Result(false, false, "none",
                "Native I2C helper unavailable (" + nativeError + "); using stock 375 mg");
        }

        // 1. Direct in-process JNI (instant if /dev/i2c-0 is directly accessible).
        int directRc = Bma253Native.writeConfig(level.rangeReg, level.int9Reg);
        if (directRc == 0) {
            return new Result(true, false, "direct",
                level.summary() + " [direct /dev/i2c-0]");
        }
        if (directRc != -EACCES && directRc != -EPERM) {
            return new Result(false, false, "direct",
                "BMA253 I2C error (" + directRc + "); using stock 375 mg");
        }

        // 2. Magisk/root su helper.
        Result su = trySu(level);
        if (su != null && su.applied) {
            return su;
        }

        return new Result(false, true, "stock",
            "Root (SU) not enabled for Kiosk Satellite — using stock 375 mg (Level 7)");
    }

    private Result trySu(Bma253Sensitivity.Level level) {
        String[] suBins = new String[] { "/sbin/su", "/system/bin/su", "/system/xbin/su", "su" };
        String script = "CLASSPATH=" + shellQuote(pkgPath)
            + " /system/bin/app_process /system/bin lenovotouch.Bma253Cli "
            + shellQuote(libPath) + " set " + level.rangeReg + " " + level.int9Reg;
        for (String su : suBins) {
            Process proc = null;
            try {
                if (su.startsWith("/") && !new java.io.File(su).exists()) {
                    continue;
                }
                proc = new ProcessBuilder(su, "-c", script).redirectErrorStream(true).start();
                boolean finished = proc.waitFor(HELPER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    continue;
                }
                String output = readAll(proc.getInputStream()).trim();
                if (proc.exitValue() == 0 && output.startsWith("ok ")) {
                    return new Result(true, false, "su",
                        level.summary() + " [root SU]");
                }
                return null;
            } catch (Throwable ignored) {
                if (proc != null) {
                    proc.destroyForcibly();
                }
            }
        }
        return null;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4096) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }
}

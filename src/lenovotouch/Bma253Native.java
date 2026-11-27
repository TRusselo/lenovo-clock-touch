// SPDX-License-Identifier: MIT
package lenovotouch;

/** JNI bridge to {@code libbma253.so} for reading/writing Bosch BMA253 tap registers over /dev/i2c-0. */
public final class Bma253Native {
    private static volatile boolean loaded;

    private Bma253Native() {}

    public static synchronized void load(String libraryPath) {
        if (!loaded) {
            System.load(libraryPath);
            loaded = true;
        }
    }

    /** Returns {@code (rangeReg << 8) | int9Reg}, or negative {@code -errno} on failure. */
    public static int readConfig() {
        return nativeReadConfig();
    }

    /** Writes REG_PMU_RANGE (0x0F) and REG_INT_9 (0x2B), returning 0 on success or {@code -errno}. */
    public static int writeConfig(int rangeReg, int int9Reg) {
        return nativeWriteConfig(rangeReg, int9Reg);
    }

    private static native int nativeReadConfig();
    private static native int nativeWriteConfig(int rangeReg, int int9Reg);
}

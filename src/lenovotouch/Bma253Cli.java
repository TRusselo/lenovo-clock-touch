// SPDX-License-Identifier: MIT
package lenovotouch;

/**
 * Tiny standalone helper run via {@code app_process} under root (Shizuku uid 0 or su)
 * when {@code /dev/i2c-0} is not directly writable by Kiosk Satellite.
 *
 * <p>Usage:
 * <pre>
 *   CLASSPATH=&lt;plugin.jar&gt; app_process /system/bin lenovotouch.Bma253Cli &lt;libbma253.so&gt; get
 *   CLASSPATH=&lt;plugin.jar&gt; app_process /system/bin lenovotouch.Bma253Cli &lt;libbma253.so&gt; set &lt;rangeHex&gt; &lt;int9Hex&gt;
 * </pre>
 */
public final class Bma253Cli {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: Bma253Cli <libPath> <get|set> [range] [int9]");
            System.exit(2);
            return;
        }
        try {
            Bma253Native.load(args[0]);
            if ("get".equals(args[1])) {
                int packed = Bma253Native.readConfig();
                if (packed < 0) {
                    System.err.println("readConfig failed: " + packed);
                    System.exit(Math.min(125, Math.max(1, -packed)));
                    return;
                }
                int range = (packed >> 8) & 0xFF;
                int int9 = packed & 0xFF;
                System.out.println("ok " + range + " " + int9);
                System.exit(0);
            } else if ("set".equals(args[1]) && args.length >= 4) {
                int range = Integer.decode(args[2]);
                int int9 = Integer.decode(args[3]);
                int rc = Bma253Native.writeConfig(range, int9);
                if (rc < 0) {
                    System.err.println("writeConfig failed: " + rc);
                    System.exit(Math.min(125, Math.max(1, -rc)));
                    return;
                }
                System.out.println("ok " + range + " " + int9);
                System.exit(0);
            } else {
                System.err.println("invalid command");
                System.exit(2);
            }
        } catch (Throwable t) {
            System.err.println("error: " + t.getMessage());
            System.exit(1);
        }
    }
}

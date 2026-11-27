// SPDX-License-Identifier: MIT
package lenovotouch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

public final class PluginLifecycleTest {
    private static final class FakeHost implements PluginHost {
        final Map<String, String> textSensors = new ConcurrentHashMap<>();
        final List<String> firedTriggers = new ArrayList<>();
        String status = "";

        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
        @Override public void status(String message, boolean error) { this.status = message; }
        @Override public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {}
        @Override public void hideOverlay(String key) {}
        @Override public void publishStatusTile(String key, String title, String level, String text) {}
        @Override public void publishTextSensor(String key, String name, String state) { textSensors.put(key, state); }
        @Override public void removeTextSensor(String key) { textSensors.remove(key); }
        @Override public void fireTrigger(String id) { firedTriggers.add(id); }
        @Override public String nativeLibraryPath(String name) { throw new UnsupportedOperationException("no native in JVM test"); }
        @Override public String packagePath() { throw new UnsupportedOperationException("no package in JVM test"); }
    }

    public static void main(String[] args) throws Exception {
        FakeHost host = new FakeHost();
        LenovoClockTouchPlugin plugin = new LenovoClockTouchPlugin();

        Map<String, Object> init = new HashMap<>();
        init.put("sensitivity", 7);
        init.put("tapWindowMs", 450);
        init.put("pauseGesturesInTester", true);

        plugin.start(host, init);
        assert host.textSensors.containsKey("sensor_status");
        assert host.textSensors.containsKey("tap_tester");
        assert !host.textSensors.containsKey("tap_details");

        // Opening settings page resets tester to idle prompt.
        Map<String, Object> openEvent = new HashMap<>();
        openEvent.put("open", true);
        plugin.onEvent("settings.page", openEvent);
        assert host.textSensors.get("tap_tester").contains("1, 2, or 3 times");

        // Verify tester readout formatting.
        String single = LenovoClockTouchPlugin.formatTesterReadout("Single", 1, new long[0], 450L, true);
        assert single.startsWith("Tap Detected: Single\n") && single.contains("gestures paused");
        String dbl = LenovoClockTouchPlugin.formatTesterReadout("Double", 2, new long[] { 310L }, 450L, false);
        assert dbl.startsWith("Tap Detected: Double\n") && dbl.contains("310 ms");

        // Verify root setup instructions stay within KS 512-char text sensor limit.
        assert Bma253Tuner.ROOT_SETUP_INSTRUCTIONS.length() <= 512
            : "ROOT_SETUP_INSTRUCTIONS too long: " + Bma253Tuner.ROOT_SETUP_INSTRUCTIONS.length();
        assert Bma253Tuner.ROOT_SETUP_INSTRUCTIONS.contains("App Launcher");
        assert Bma253Tuner.ROOT_SETUP_INSTRUCTIONS.contains("30 seconds");

        // Configure updates cleanly.
        init.put("tapWindowMs", 350);
        plugin.configure(init);

        // Execute rejects since plugin uses gesture triggers only.
        boolean threw = false;
        try {
            plugin.execute("anything", new HashMap<>());
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assert threw;

        plugin.stop();
    }
}

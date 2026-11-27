// SPDX-License-Identifier: MIT
package lenovotouch;

import android.content.Context;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Kiosk Satellite plugin for Lenovo Smart Clock Gen 1 accelerometer tap gestures.
 *
 * <ul>
 *   <li>Receives hardware single-tap interrupts via {@code SparrowService} (no root required).</li>
 *   <li>Always detects Single, Double, and Triple tap Gesture triggers.</li>
 *   <li>Tunes Bosch BMA253 hardware tap sensitivity (1..10) over /dev/i2c-0 (0x18) using direct access or Magisk SU.</li>
 *   <li>Provides a single live Tap Tester readout on the settings page.</li>
 * </ul>
 */
public final class LenovoClockTouchPlugin implements KioskPlugin {
    private static final String CONTEXT_OVERLAY_KEY = "ctx";
    private static final String SETUP_NOTE =
        "Configure tap actions in Gestures > Add Gesture > Plugin Trigger (Single tap, Double tap, Triple tap).";
    private static final String TESTER_IDLE =
        "Tap the clock 1, 2, or 3 times to test sensitivity and timing.";

    private volatile PluginHost host;
    private volatile boolean stopping;
    private final Map<String, Object> settings = new ConcurrentHashMap<>();

    private ScheduledExecutorService worker;
    private TapRecognizer recognizer;
    private SparrowTapClient sparrowClient;
    private Bma253Tuner tuner;

    private volatile boolean sparrowConnected;
    private volatile String sparrowStatus = "Connecting to SparrowService...";
    private volatile String tuningStatus = "Checking BMA253 sensitivity...";
    private volatile boolean rootSetupVisible;
    private volatile boolean settingsPageOpen;

    private final AtomicInteger detectedCounter = new AtomicInteger();
    private volatile int lastAppliedSensitivity = -1;

    @Override
    public void start(PluginHost host, Map<String, Object> initial) {
        this.host = host;
        this.stopping = false;
        this.sparrowConnected = false;
        this.settingsPageOpen = false;
        this.rootSetupVisible = false;
        this.lastAppliedSensitivity = -1;
        this.detectedCounter.set(0);

        settings.clear();
        if (initial != null) {
            for (Map.Entry<String, Object> e : initial.entrySet()) {
                if (e.getValue() != null) {
                    settings.put(e.getKey(), e.getValue());
                }
            }
        }

        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lenovo-clock-touch");
            t.setDaemon(true);
            return t;
        });

        recognizer = new TapRecognizer(worker, this::onTapSequenceDetected);
        tuner = new Bma253Tuner(host);

        applyRecognizerSettings();
        publishInitialReadings();
        updateHostStatus();

        // Check/apply hardware sensitivity off the start() callback thread.
        final int targetSensitivity = sensitivityLevel();
        worker.execute(() -> applyHardwareSensitivity(targetSensitivity));

        // Obtain Android Context via a 1x1 invisible transient overlay (public SDK pattern).
        acquireContextAndConnect();
    }

    @Override
    public void configure(Map<String, Object> next) {
        if (next != null) {
            for (Map.Entry<String, Object> e : next.entrySet()) {
                if (e.getValue() != null) {
                    settings.put(e.getKey(), e.getValue());
                }
            }
        }
        applyRecognizerSettings();

        final int targetSensitivity = sensitivityLevel();
        if (targetSensitivity != lastAppliedSensitivity && worker != null && !stopping) {
            worker.execute(() -> applyHardwareSensitivity(targetSensitivity));
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        throw new IllegalArgumentException(
            "Lenovo Clock Touch uses Gesture triggers (Gestures > Add Gesture > Plugin Trigger).");
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if (stopping || event == null) return;
        if ("settings.page".equals(event)) {
            boolean open = payload != null && Boolean.TRUE.equals(payload.get("open"));
            settingsPageOpen = open;
            if (open) {
                publishText("tap_tester", "Tap tester", TESTER_IDLE);
                publishSensorStatus();
            }
        }
    }

    @Override
    public void stop() {
        stopping = true;
        if (recognizer != null) {
            recognizer.reset();
        }
        SparrowTapClient client = sparrowClient;
        sparrowClient = null;
        if (client != null) {
            client.stop();
        }
        ScheduledExecutorService exec = worker;
        worker = null;
        if (exec != null) {
            exec.shutdownNow();
            try {
                exec.awaitTermination(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        sparrowConnected = false;
    }

    // --- Context acquisition & SparrowService connection ---

    private void acquireContextAndConnect() {
        final PluginHost h = host;
        if (h == null || stopping) return;
        try {
            h.showOverlay(CONTEXT_OVERLAY_KEY, OverlaySpec.at(OverlaySpec.TOP_LEFT).size(1, 1).touchable(false), new OverlayFactory() {
                @Override
                public View create(Context context, KsTheme theme) {
                    final Context appCtx = context.getApplicationContext();
                    ScheduledExecutorService exec = worker;
                    if (exec != null && !stopping) {
                        exec.execute(() -> connectSparrow(appCtx));
                    }
                    View v = new View(context);
                    v.post(() -> {
                        try {
                            h.hideOverlay(CONTEXT_OVERLAY_KEY);
                        } catch (RuntimeException ignored) {
                        }
                    });
                    return v;
                }
            });
        } catch (Throwable t) {
            sparrowStatus = "Unable to acquire Android context: " + t.getMessage();
            publishSensorStatus();
            updateHostStatus();
        }
    }

    private synchronized void connectSparrow(Context appContext) {
        if (stopping || sparrowClient != null || worker == null) return;
        sparrowClient = new SparrowTapClient(appContext, worker, new SparrowTapClient.Callback() {
            @Override
            public void onConnected() {
                sparrowConnected = true;
                sparrowStatus = "Connected (SparrowService BMA253)";
                publishSensorStatus();
                updateHostStatus();
                log("Connected to Lenovo Smart Clock SparrowService tap listener.");
            }

            @Override
            public void onDisconnected(String reason) {
                sparrowConnected = false;
                sparrowStatus = reason;
                publishSensorStatus();
                updateHostStatus();
                log("SparrowService: " + reason);
            }

            @Override
            public void onTap(int rawEvent, long elapsedRealtimeMs) {
                if (stopping) return;
                TapRecognizer rec = recognizer;
                if (rec != null) {
                    rec.onRawTap(elapsedRealtimeMs);
                }
            }
        });
        sparrowClient.start();
    }

    // --- Tap gesture handling & live Tap Tester ---

    private void onTapSequenceDetected(int count, long[] gapsMs) {
        if (stopping) return;
        int n = detectedCounter.incrementAndGet();
        String label = TapRecognizer.labelForCount(count);
        String triggerId = TapRecognizer.triggerIdForCount(count);
        boolean suppressGesture = settingsPageOpen && pauseGesturesInTester();

        String testerText = formatTesterReadout(label, n, gapsMs, suppressGesture);
        publishText("tap_tester", "Tap tester", testerText);

        if (!suppressGesture && triggerId != null) {
            try {
                host.fireTrigger(triggerId);
            } catch (Throwable t) {
                log("fireTrigger(" + triggerId + ") failed: " + t.getMessage());
            }
        }
    }

    static String formatTesterReadout(String label, int seqNum, long[] gapsMs, long windowMs, boolean paused) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("Tap Detected: ").append(label).append('\n');
        sb.append('#').append(seqNum).append(" · ").append(time);
        if (gapsMs != null && gapsMs.length > 0) {
            sb.append(" · gap ");
            for (int i = 0; i < gapsMs.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(gapsMs[i]).append(" ms");
            }
        }
        sb.append(" · window ").append(windowMs).append(" ms");
        if (paused) {
            sb.append(" (gestures paused)");
        }
        return sb.toString();
    }

    private String formatTesterReadout(String label, int seqNum, long[] gapsMs, boolean paused) {
        return formatTesterReadout(label, seqNum, gapsMs, tapWindowMs(), paused);
    }

    // --- Hardware sensitivity tuning ---

    private void applyHardwareSensitivity(int levelNum) {
        if (stopping) return;
        Bma253Sensitivity.Level level = Bma253Sensitivity.forLevel(levelNum);
        lastAppliedSensitivity = level.level;

        Bma253Tuner.Result res = tuner.apply(level);
        tuningStatus = res.message;
        log("BMA253 sensitivity -> " + res.message);

        if (res.rootRequired) {
            rootSetupVisible = true;
            publishText("root_setup", "Root (SU) setup for sensitivity", Bma253Tuner.ROOT_SETUP_INSTRUCTIONS);
        } else if (rootSetupVisible) {
            rootSetupVisible = false;
            removeText("root_setup");
        }

        publishSensorStatus();
        updateHostStatus();
    }

    // --- Settings helpers & readings ---

    private void applyRecognizerSettings() {
        TapRecognizer rec = recognizer;
        if (rec != null) {
            rec.configure(tapWindowMs(), TapRecognizer.DEFAULT_MAX_TAPS);
        }
    }

    private int sensitivityLevel() {
        Object v = settings.get("sensitivity");
        int n = v instanceof Number ? ((Number) v).intValue() : Bma253Sensitivity.DEFAULT_LEVEL;
        return Bma253Sensitivity.clamp(n);
    }

    private long tapWindowMs() {
        Object v = settings.get("tapWindowMs");
        long n = v instanceof Number ? ((Number) v).longValue() : TapRecognizer.DEFAULT_WINDOW_MS;
        return Math.max(150L, Math.min(1000L, n));
    }

    private boolean pauseGesturesInTester() {
        Object v = settings.get("pauseGesturesInTester");
        return !(v instanceof Boolean) || (Boolean) v;
    }

    private void publishInitialReadings() {
        publishSensorStatus();
        publishText("tap_tester", "Tap tester", TESTER_IDLE);
    }

    private void publishSensorStatus() {
        publishText("sensor_status", "Sensor & sensitivity", sparrowStatus + " · " + tuningStatus);
        try {
            String tileLevel = sparrowConnected ? "on" : "warn";
            String tileText = sparrowConnected
                ? "Ready (sensitivity " + sensitivityLevel() + "/10)"
                : sparrowStatus;
            host.publishStatusTile("tap_sensor", "Clock Tap", tileLevel, tileText);
        } catch (Throwable ignored) {
        }
    }

    private void updateHostStatus() {
        if (stopping) return;
        try {
            if (sparrowConnected) {
                host.status("Ready — " + SETUP_NOTE, false);
            } else {
                host.status(sparrowStatus, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void publishText(String key, String name, String value) {
        if (stopping) return;
        try {
            host.publishTextSensor(key, name, value);
        } catch (Throwable ignored) {
        }
    }

    private void removeText(String key) {
        if (stopping) return;
        try {
            host.removeTextSensor(key);
        } catch (Throwable ignored) {
        }
    }

    private void log(String msg) {
        try {
            PluginHost h = host;
            if (h != null) h.log(msg);
        } catch (Throwable ignored) {
        }
    }
}

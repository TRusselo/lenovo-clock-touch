// SPDX-License-Identifier: MIT
package lenovotouch;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Groups raw hardware tap interrupts into Single (1), Double (2), or Triple (3) tap gestures.
 *
 * <p>Timing model:
 * <ul>
 *   <li>A short bounce guard ({@code minGapMs}) ignores impossible sub-60 ms glitches.</li>
 *   <li>Each accepted tap increments the pending count and restarts the {@code tapWindowMs} timer.</li>
 *   <li>If {@code count == maxTapCount}, the gesture commits immediately with zero wait.</li>
 *   <li>Otherwise, when {@code tapWindowMs} elapses with no further tap, the gesture commits.</li>
 * </ul>
 */
public final class TapRecognizer {
    public static final long DEFAULT_MIN_GAP_MS = 60L;
    public static final long DEFAULT_WINDOW_MS = 450L;
    public static final int DEFAULT_MAX_TAPS = 3;

    public interface Listener {
        /** Called when a tap sequence completes. {@code count} is 1, 2, or 3; {@code gapsMs} has length {@code count - 1}. */
        void onTapSequence(int count, long[] gapsMs);
    }

    public interface Timer {
        Cancellable schedule(Runnable task, long delayMs);
    }

    public interface Cancellable {
        void cancel();
    }

    private final Timer timer;
    private final Listener listener;
    private final long minGapMs;

    private long tapWindowMs = DEFAULT_WINDOW_MS;
    private int maxTapCount = DEFAULT_MAX_TAPS;

    private int pendingCount = 0;
    private long lastTapMs = Long.MIN_VALUE;
    private final long[] gaps = new long[3];
    private Cancellable pendingTimer;
    private long sequenceGeneration = 0;

    public TapRecognizer(ScheduledExecutorService executor, Listener listener) {
        this((task, delayMs) -> {
            ScheduledFuture<?> f = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        }, listener, DEFAULT_MIN_GAP_MS);
    }

    public TapRecognizer(Timer timer, Listener listener, long minGapMs) {
        this.timer = timer;
        this.listener = listener;
        this.minGapMs = Math.max(0L, minGapMs);
    }

    public synchronized void configure(long tapWindowMs, int maxTapCount) {
        this.tapWindowMs = Math.max(100L, Math.min(2000L, tapWindowMs));
        this.maxTapCount = Math.max(1, Math.min(3, maxTapCount));
    }

    public synchronized long tapWindowMs() {
        return tapWindowMs;
    }

    public synchronized int maxTapCount() {
        return maxTapCount;
    }

    /** Record one raw hardware tap pulse at monotonic time {@code nowMs}. */
    public void onRawTap(long nowMs) {
        int commitCount = 0;
        long[] commitGaps = null;

        synchronized (this) {
            if (lastTapMs != Long.MIN_VALUE) {
                long delta = nowMs - lastTapMs;
                if (delta >= 0 && delta < minGapMs) {
                    return;
                }
            }
            if (pendingTimer != null) {
                pendingTimer.cancel();
                pendingTimer = null;
            }

            if (pendingCount > 0 && pendingCount <= gaps.length) {
                gaps[pendingCount - 1] = Math.max(0L, nowMs - lastTapMs);
            }
            lastTapMs = nowMs;
            pendingCount++;

            if (pendingCount >= maxTapCount) {
                commitCount = pendingCount;
                commitGaps = Arrays.copyOf(gaps, Math.max(0, commitCount - 1));
                pendingCount = 0;
                sequenceGeneration++;
            } else {
                final long gen = ++sequenceGeneration;
                final long delay = tapWindowMs;
                pendingTimer = timer.schedule(() -> commitIfCurrent(gen), delay);
            }
        }

        if (commitCount > 0) {
            listener.onTapSequence(commitCount, commitGaps);
        }
    }

    private void commitIfCurrent(long gen) {
        int commitCount = 0;
        long[] commitGaps = null;
        synchronized (this) {
            if (gen != sequenceGeneration || pendingCount <= 0) {
                return;
            }
            commitCount = pendingCount;
            commitGaps = Arrays.copyOf(gaps, Math.max(0, commitCount - 1));
            pendingCount = 0;
            pendingTimer = null;
        }
        listener.onTapSequence(commitCount, commitGaps);
    }

    public synchronized void reset() {
        sequenceGeneration++;
        pendingCount = 0;
        lastTapMs = Long.MIN_VALUE;
        if (pendingTimer != null) {
            pendingTimer.cancel();
            pendingTimer = null;
        }
    }

    public static String labelForCount(int count) {
        switch (count) {
            case 1: return "Single";
            case 2: return "Double";
            case 3: return "Triple";
            default: return count + "x";
        }
    }

    public static String triggerIdForCount(int count) {
        switch (count) {
            case 1: return "singleTap";
            case 2: return "doubleTap";
            case 3: return "tripleTap";
            default: return null;
        }
    }
}

// SPDX-License-Identifier: MIT
package lenovotouch;

import java.util.ArrayList;
import java.util.List;

public final class TapRecognizerTest {
    private static final class FakeTimer implements TapRecognizer.Timer {
        Runnable scheduled;
        long scheduledDelay;
        boolean canceled;

        @Override
        public TapRecognizer.Cancellable schedule(Runnable task, long delayMs) {
            this.scheduled = task;
            this.scheduledDelay = delayMs;
            this.canceled = false;
            return () -> canceled = true;
        }

        void fire() {
            assert scheduled != null && !canceled : "expected active timer";
            Runnable r = scheduled;
            scheduled = null;
            r.run();
        }
    }

    public static void main(String[] args) {
        testSingleDoubleTriple();
        testMaxTapImmediateFire();
        testBounceIgnored();
        testResetCancelsPending();
        testLabelsAndTriggers();
    }

    private static void testSingleDoubleTriple() {
        FakeTimer timer = new FakeTimer();
        List<String> events = new ArrayList<>();
        TapRecognizer rec = new TapRecognizer(timer, (count, gaps) ->
            events.add(count + ":" + gaps.length), 60L);
        rec.configure(450L, 3);

        // Single tap waits for window.
        rec.onRawTap(1000L);
        assert events.isEmpty();
        assert timer.scheduledDelay == 450L;
        timer.fire();
        assert events.size() == 1 && "1:0".equals(events.get(0));

        // Double tap resets timer and commits on expiry.
        rec.onRawTap(2000L);
        rec.onRawTap(2320L);
        assert events.size() == 1;
        timer.fire();
        assert events.size() == 2 && "2:1".equals(events.get(1));

        // Triple tap commits immediately on 3rd tap without waiting for timer.
        rec.onRawTap(4000L);
        rec.onRawTap(4250L);
        rec.onRawTap(4500L);
        assert events.size() == 3 && "3:2".equals(events.get(2));
        assert timer.canceled || timer.scheduled == null;
    }

    private static void testMaxTapImmediateFire() {
        FakeTimer timer = new FakeTimer();
        List<Integer> counts = new ArrayList<>();
        TapRecognizer rec = new TapRecognizer(timer, (count, gaps) -> counts.add(count), 60L);

        // Max 1: single tap fires immediately with zero timer scheduled.
        rec.configure(450L, 1);
        rec.onRawTap(100L);
        assert counts.size() == 1 && counts.get(0) == 1;
        assert timer.scheduled == null;

        // Max 2: double tap fires immediately on 2nd tap.
        rec.configure(450L, 2);
        rec.onRawTap(1000L);
        assert counts.size() == 1;
        rec.onRawTap(1300L);
        assert counts.size() == 2 && counts.get(1) == 2;
    }

    private static void testBounceIgnored() {
        FakeTimer timer = new FakeTimer();
        List<Integer> counts = new ArrayList<>();
        TapRecognizer rec = new TapRecognizer(timer, (count, gaps) -> counts.add(count), 60L);
        rec.configure(400L, 3);

        rec.onRawTap(1000L);
        rec.onRawTap(1030L); // < 60ms bounce -> ignored
        timer.fire();
        assert counts.size() == 1 && counts.get(0) == 1 : "bounce should be ignored, got " + counts;
    }

    private static void testResetCancelsPending() {
        FakeTimer timer = new FakeTimer();
        List<Integer> counts = new ArrayList<>();
        TapRecognizer rec = new TapRecognizer(timer, (count, gaps) -> counts.add(count), 60L);
        rec.configure(400L, 3);

        rec.onRawTap(1000L);
        rec.reset();
        assert timer.canceled;
        assert counts.isEmpty();
    }

    private static void testLabelsAndTriggers() {
        assert "Single".equals(TapRecognizer.labelForCount(1));
        assert "Double".equals(TapRecognizer.labelForCount(2));
        assert "Triple".equals(TapRecognizer.labelForCount(3));
        assert "singleTap".equals(TapRecognizer.triggerIdForCount(1));
        assert "doubleTap".equals(TapRecognizer.triggerIdForCount(2));
        assert "tripleTap".equals(TapRecognizer.triggerIdForCount(3));
    }
}

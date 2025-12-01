package src;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Vector;

class BitrateChange {
    int bitrate;
    long timestamp;
    BitrateChange(int b, long t) {
        bitrate = b;
        timestamp = t;
    }
}

/**
 * ExpertSystem runs a periodic inference step over all clients
 * and adjusts the global bitrate based on rule-based logic.
 */
public class ExpertSystem {

    private final VideoStreamer streamer;

    private int bitrate;
    private final int minBitrate;
    private final int maxBitrate;
    private final int increment;
    private final int iterationMs;

    private volatile boolean running = false;
    private Thread loopThread;

    // For controlling oscillation
    private final Queue<BitrateChange> history = new LinkedList<>();
    private long lastChange = 0;

    // Streak tracking
    private int healthyStreak = 0;
    private int noBufferingStreak = 0;

    // Grace period for new clients
    private final int graceCycles = 5;

    public ExpertSystem(
            VideoStreamer streamer,
            int initialBitrate,
            int min,
            int max,
            int inc,
            int iter
    ) {
        this.streamer = streamer;
        this.bitrate = initialBitrate;
        this.minBitrate = min;
        this.maxBitrate = max;
        this.increment = inc;
        this.iterationMs = iter;
    }

    public void Start() {
        if (running) return;
        running = true;

        loopThread = new Thread(this::loop, "ExpertSystemLoop");
        loopThread.setDaemon(false);
        loopThread.start();
    }

    public void End() {
        running = false;
        try {
            if (loopThread != null) loopThread.join();
        } catch (InterruptedException ignored) {}
    }

    private void loop() {
        while (running) {

            long start = System.currentTimeMillis();
            try {
                runOnce();
            } catch (Exception e) {
                System.err.println("[EXPERT] Error: " + e.getMessage());
            }

            long elapsed = System.currentTimeMillis() - start;
            long sleep = iterationMs - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ----------------------------------------------------------------------
    // One inference step
    // ----------------------------------------------------------------------
    private void runOnce() {

        Vector<Client> clients = new Vector<>(streamer.getClients());
        if (clients.isEmpty()) {
            return;
        }

        int total = clients.size();
        int activeCount = 0;
        int bufferingCount = 0;
        int missingCount = 0;

        int sumCache = 0;

        for (Client c : clients) {

            if (c.inGracePeriod(graceCycles, iterationMs)) {
                continue;
            }

            activeCount++;

            if (!recent(c)) {
                missingCount++;
            }

            if (c.isBuffering()) {
                bufferingCount++;
            }

            sumCache += c.getCachePercent();
        }

        if (activeCount == 0) return;

        int avgCache = sumCache / activeCount;
        int percentBuffering = (int)((bufferingCount * 100.0) / activeCount);
        int percentMissing = (int)((missingCount * 100.0) / activeCount);

        boolean changed = false;

        // ============================================================
        // Rule 1: If >X% buffering, decrease bitrate
        // ============================================================
        if (Rule1_BufferingTooHigh(percentBuffering)) {
            changed = true;
        }

        // ============================================================
        // Rule 2: If <X% buffering for Y cycles, increase bitrate
        // ============================================================
        if (!changed && Rule2_BufferingLow(percentBuffering)) {
            changed = true;
        }

        // ============================================================
        // Rule 3: If buffering spike after increase, revert bitrate
        // ============================================================
        if (!changed && Rule3_RevertIfSpike(percentBuffering)) {
            changed = true;
        }

        // ============================================================
        // Rule 4: If too many bitrate adjustments recently, freeze
        // ============================================================
        if (!changed && Rule4_TooManyChanges()) {
            changed = true;
        }

        // ============================================================
        // Rule 5: If average cache low (proxy for buffer length low), decrease
        // ============================================================
        if (!changed && Rule5_CacheLow(avgCache)) {
            changed = true;
        }

        // ============================================================
        // Rule 6: If average cache high, increase or maintain
        // ============================================================
        if (!changed && Rule6_CacheHigh(avgCache)) {
            changed = true;
        }

        // ============================================================
        // Rule 7: If missing telemetry from X% of clients, precaution decrease
        // ============================================================
        if (!changed && Rule7_MissingTelemetry(percentMissing)) {
            changed = true;
        }

        // ============================================================
        // Rule 8: If no buffering for Y minutes, mark baseline
        // ============================================================
        if (!changed) {
            Rule8_RecordBaseline(percentBuffering);
        }

        // ============================================================
        // Rule 9: If at minimum bitrate and still buffering, alert
        // ============================================================
        if (!changed) {
            Rule9_MinBitrateStillBuffering(percentBuffering);
        }

        // ============================================================
        // Rule 10: If stable for Y minutes, log session stable
        // ============================================================
        if (!changed) {
            Rule10_StableSession(percentBuffering);
        }

        System.out.println();
        Main.printClientStats(streamer.getClients());
    }

    private boolean recent(Client c) {
        return System.currentTimeMillis() - c.getLastUpdateMs() < 15000;
    }

    private boolean canChange() {
        return System.currentTimeMillis() - lastChange >= 5000;
    }

    private void applyChange(int newRate) {

        if (newRate == bitrate) return;

        System.out.println();
        System.out.println("====================================================");
        System.out.println("      BITRATE CHANGE: " + bitrate + " → " + newRate + " kbps");
        System.out.println("====================================================");
        System.out.println();

        bitrate = newRate;
        lastChange = System.currentTimeMillis();
        history.add(new BitrateChange(newRate, lastChange));

        // trim 1-minute window
        long cutoff = lastChange - 60000;
        while (!history.isEmpty() && history.peek().timestamp < cutoff) {
            history.poll();
        }

        streamer.setBitrate(newRate);
    }

    // ----------------------------------------------------------------------
    // Rule implementations
    // ----------------------------------------------------------------------

    // Rule 1: >X% buffering → decrease
    private boolean Rule1_BufferingTooHigh(int percentBuffering) {
        if (percentBuffering > 40 && canChange()) {
            applyChange(Math.max(minBitrate, bitrate - increment));
            return true;
        }
        return false;
    }

    // Rule 2: <X% buffering for Y cycles → increase
    private boolean Rule2_BufferingLow(int percentBuffering) {
        if (percentBuffering == 0) healthyStreak++;
        else healthyStreak = 0;

        if (healthyStreak >= 3 && canChange()) {
            healthyStreak = 0;
            applyChange(Math.min(maxBitrate, bitrate + increment));
            return true;
        }
        return false;
    }

    // Rule 3: spike after increase → revert
    private boolean Rule3_RevertIfSpike(int percentBuffering) {
        if (history.size() < 1) return false;

        BitrateChange last = history.peek();
        long dt = System.currentTimeMillis() - last.timestamp;

        if (dt < 15000 && percentBuffering > 30) {
            applyChange(Math.max(minBitrate, bitrate - increment));
            return true;
        }
        return false;
    }

    // Rule 4: too many changes → freeze
    private boolean Rule4_TooManyChanges() {
        if (history.size() >= 4) {
            long now = System.currentTimeMillis();
            long first = history.peek().timestamp;
            if (now - first < 30000) {
                System.out.println("[EXPERT] Too many changes recently, holding bitrate.");
                return true;
            }
        }
        return false;
    }

    // Rule 5: low cache → decrease
    private boolean Rule5_CacheLow(int avgCache) {
        if (avgCache < 40 && canChange()) {
            applyChange(Math.max(minBitrate, bitrate - increment));
            return true;
        }
        return false;
    }

    // Rule 6: high cache → optional slight increase
    private boolean Rule6_CacheHigh(int avgCache) {
        if (avgCache > 90 && canChange()) {
            applyChange(Math.min(maxBitrate, bitrate + increment));
            return true;
        }
        return false;
    }

    // Rule 7: missing telemetry → precaution decrease
    private boolean Rule7_MissingTelemetry(int missingPercent) {
        if (missingPercent > 30 && canChange()) {
            applyChange(Math.max(minBitrate, bitrate - increment));
            return true;
        }
        return false;
    }

    // Rule 8: no buffering for Y minutes → record baseline
    private void Rule8_RecordBaseline(int percentBuffering) {
        if (percentBuffering == 0) noBufferingStreak++;
        else noBufferingStreak = 0;

        int cyclesNeeded = (60_000 / iterationMs); // 1 minute
        if (noBufferingStreak >= cyclesNeeded) {
            System.out.println("[EXPERT] Baseline stable bitrate: " + bitrate);
            noBufferingStreak = 0;
        }
    }

    // Rule 9: at min bitrate + buffering persists
    private void Rule9_MinBitrateStillBuffering(int percentBuffering) {
        if (bitrate == minBitrate && percentBuffering > 50) {
            System.out.println("[EXPERT] WARNING: minimum bitrate but buffering persists.");
        }
    }

    // Rule 10: fully stable for Y minutes
    private void Rule10_StableSession(int percentBuffering) {
        int cyclesNeeded = (60_000 / iterationMs);
        if (percentBuffering == 0) healthyStreak++;
        else healthyStreak = 0;

        if (healthyStreak >= cyclesNeeded) {
            System.out.println("[EXPERT] Session is stable at current bitrate.");
            healthyStreak = 0;
        }
    }
}

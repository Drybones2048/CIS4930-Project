package src;

public class Client {

    private final String ipAddress;

    // Telemetry extracted by VlcMonitor
    private int cachePercent = 100;
    private boolean isBuffering = false;
    private int droppedFrames = 0;
    private int lastDroppedFrames = 0;

    private boolean updated = false;
    private long lastUpdateMs = System.currentTimeMillis();

    // Tracking how long the client has existed
    private long firstSeen = System.currentTimeMillis();

    public Client(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    // ----------------------------------------------------------------------
    // Telemetry setters
    // ----------------------------------------------------------------------

    public void setCachePercent(int percent) {
        this.cachePercent = Math.max(0, Math.min(100, percent));
    }

    public void setIsBuffering(boolean buffering) {
        this.isBuffering = buffering;
    }

    public void setDroppedFrames(int count) {
        this.lastDroppedFrames = this.droppedFrames;
        this.droppedFrames = Math.max(0, count);
    }

    public void markUpdated() {
        updated = true;
        lastUpdateMs = System.currentTimeMillis();
    }

    public void resetUpdatedFlag() {
        updated = false;
    }

    // ----------------------------------------------------------------------
    // Getters
    // ----------------------------------------------------------------------

    public int getCachePercent() {
        return cachePercent;
    }

    public boolean isBuffering() {
        return isBuffering;
    }

    public int getDroppedFrames() {
        return droppedFrames;
    }

    public int getDroppedDelta() {
        return Math.max(0, droppedFrames - lastDroppedFrames);
    }

    public long getLastUpdateMs() {
        return lastUpdateMs;
    }

    // ----------------------------------------------------------------------
    // Derived health logic
    // ----------------------------------------------------------------------

    public boolean isHealthy() {
        // High cache, no buffering, minimal drops
        return cachePercent >= 80 &&
                !isBuffering &&
                getDroppedDelta() == 0;
    }

    public boolean isStruggling() {
        // Buffering or low cache or rising drops
        return isBuffering ||
                cachePercent <= 40 ||
                getDroppedDelta() > 3;
    }

    // ----------------------------------------------------------------------
    // Grace period for new clients
    // ----------------------------------------------------------------------

    public boolean inGracePeriod(int graceCycles, int iterationMs) {
        long msAlive = System.currentTimeMillis() - firstSeen;
        return msAlive < (long) graceCycles * iterationMs;
    }
}

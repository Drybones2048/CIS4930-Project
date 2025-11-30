package src;

public class Client {
    private String ipAddress; // Client identification
    public int bufferDuration; // how long client is buffering in seconds or milliseconds, used to read average buffer length

    // This is used as a handshake to check if a client has stalled and not updated
    // When updating the client information (even if no changes are made), it should be set to true
    // When ExpertSystem reads the client's information every iteration (i.e., every 5 seconds), it sets it to false
    // It will know a client has stalled/is not responding if it reads a false isUpdated value.
    public boolean isUpdated;

    private long lastUpdateMs;// track last update time for timeouts if needed

    // Constructor
    public Client(String ipAddress) {
        this.ipAddress = ipAddress;
        this.bufferDuration = 0;
        this.isUpdated = false;
        this.lastUpdateMs = 0L;
    }

    // Called whenever new info comes from the client
    // bufferDuration is the "how long they are buffering" value
    public void updateStatus(int bufferDuration) {
        this.bufferDuration = bufferDuration;
        this.isUpdated = true;
        this.lastUpdateMs = System.currentTimeMillis();
    }

    // Called by ExpertSystem after it reads the client this iteration
    // so that on the next tick, we can detect if the client failed to update
    public void resetUpdatedFlag() {
        this.isUpdated = false;
    }

    // helper that checks if the client currently buffering
    public boolean isBuffering() {
        return bufferDuration > 0;
    }

    // helper for potential timeout checks, if we eed it
    public boolean isAlive(long nowMs, long timeoutMs) {
        return (nowMs - lastUpdateMs) <= timeoutMs;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public long getLastUpdateMs() {
        return lastUpdateMs;
    }
}

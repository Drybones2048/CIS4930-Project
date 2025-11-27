package src;
import java.util.Queue;
import java.util.LinkedList;

class BitrateChange {
    int bitrate;
    long timestamp;
    BitrateChange(int bitrate, long timestamp)
    { this.bitrate = bitrate; this.timestamp = timestamp; }
}

public class ExpertSystem {
    private VideoStreamer streamer;
    private String compressionSettings = "medium";

    // Bitrate Config
    private int bitrate;
    private int maxBitrate;
    private int minBitrate;
    private int bitrateIncrement;

    // Streamer settings
    private int baselineBitrate;
    private int previousBitrate;

    // Rule variables
    private long lastBuffer = System.currentTimeMillis(); // Rule 8
    private int consecutiveBufferFree = 0; // Rule 2
    Queue<BitrateChange> bitrateChanges = new LinkedList<>();

    // Inference Engine
    private boolean isRunning = false;
    private Thread loopThread;
    int iterationLength; // milliseconds between loops

    // Constructor
    public ExpertSystem(VideoStreamer streamer, int bitrate, int minBitrate, int maxBitrate, int bitrateIncrement, int iterationLength) {
        this.streamer = streamer;
        this.bitrate = bitrate;
        this.minBitrate = minBitrate;
        this.maxBitrate = maxBitrate;
        this.bitrateIncrement = bitrateIncrement;
        this.iterationLength = iterationLength; // how many ms it should wait before re-running engine
    }


    // Bitrate Change Diagnostics
    private void RuleStreamIssue() { // Rule 9
        // If min level bitrate and still issues
        // pause stream -- for now just do an all caps print
    }

    private boolean RuleRevertBitrate(int lastChange, float lastBufferPercent, int lastBufferLength) { // Rule 3
        // takes in lastChange, which is either -1, 0, +1
        // representing -1 increment, +1, etc.
        return false;
        // Check that bitrate was changed (!= 0)
        // Calculate percent of clients buffering and the average buffer length
            // this is the same code as as the 4 RuleBuffering rules below, do those first and copy it
        // if either increased compared to lastBufferPercent and lastBufferLength, return true (loop will revert)
    }

    private boolean RuleSteadyBitrate() { // Rule 4
        // The queue used for this is bitrateChanges
        // .poll() pops front, .peek() returns front, .size() gives size
        // The queue stores bitrateChanges (see top of file), which has two items:
            // .bitrate = the bitrate in kbps (or whatever unit everywhere else uses)
            // .timestamp = millisecond system clock timestamp (same format returned by System.currentTimeMillis())
        return false;
        // Define time window (i.e., 1 min rolling count of bitrate changes)
            // Every bitrate adjustment, push bitrate to queue w/ time stamp (DONE IN LOOP, BY FINN, NOT U)
        // If timestamp is more than X seconds old (compared to current time stamp), remove from queue
            // Just check front of queue to see if it's too old, if it is then kick it off and repeat
            // if it's not, then you know everything after it is younger and also fine
            // this needs a loop
        // If more than Y items in queue (q.size()), bitrate is changing too rapidly so set it baseline (baselineBitrate)
            // if not set, choose smallest item in queue
            // this is the one function that directly updates bitrate itself (since it locks after)
            // then return true (loop will set the flag)
    }


    // Bitrate Adjustments
    private boolean RulePercentBufferingDecrease(float[] bufferPercent) { // Rule 1
        return false;
        // Loop through clients
        // If X% of them are currently buffering, decrease bitrate (return true)
        // Store % in bufferPercent[0], regardless if true or not
            // NOTE: i use an array because java doesn't have passing by reference
            // It's just a workaround to have two return values
            // it's not an actual array
        // ANOTHER NOTE:
            // make sure bufferPercent[0] IS NOT -1
            // -1 is the value i initialize it to, before it's set by one of these funcs
        // LAST NOTE:
            // this all applies to the three functions below this one
    }

    private boolean RulePercentBufferingIncrease(float[] bufferPercent) { // Rule 2
        return false;
        // If <X% are buffering
        // Increase consecutiveBufferFree by 1
        // if consecutiveBufferFree >Y intervals, increase bitrate (return true)
        // Store % in bufferPercent[0]
    }

    private boolean RuleBufferLengthDecrease(int[] bufferLength) { // Rule 5
        return false;
        // Calculate average client buffer rate (from int in each client?)
        // if < X seconds, decrease bitrate (return true)
        // Store average in bufferLength[0]
    }

    private boolean RuleBufferLengthIncrease(int[] bufferLength) { // Rule 6
        return false;
        // Calculate average client buffer rate
        // If > X seconds, return true
        // Store average in bufferLength[0]
    }

    private boolean RuleNetworkCongestionDecrease() { // Rule 7
        return false;
        // Each client has an isUpdated bool that works like a handshake
        // When they change their info, they set it to True
        // When we read it, they set it to false
        // If more than X% are read as false (unupdated),
        // Then reduce bitrate bc of possible congestion
    }


    // Current Bitrate Stability Diagnostics
    private void RuleSetBaselineBitrate() { // Rule 8
        // If anyone is buffering, set lastBuffer timestamp to now (global var)
        // Check if currentTime - lastBuffer > X minutes
        // If it is, set current bitrate as baseline (baselineBitrate)
    }

    private void RuleLogStableSession() { // Rule 10
        // Same as above but a debug log instead?
        // Not totally sure the difference between no buffering and stable playback
        // But i assume just same as above
    }


    // Inference Engine Loop
    public void Start() {
        if (loopThread != null && loopThread.isAlive()) return;

        isRunning = true;
        loopThread = new Thread(this::InferenceEngine);
        loopThread.start();
    }

    private void InferenceEngine() {
        // Loop variables
        int bitrateChange = 0; // either +1, 0, -1 for increments
        int lastChange;
        float[] lastBufferPercent = {-1};
        int[] lastBufferLength = {-1};

        // Main loop
        while (isRunning) {
            // Apply bitrateChange
            if (bitrateChange != 0) {
                bitrate = bitrate + bitrateChange * bitrateIncrement;
                if (bitrate > maxBitrate) bitrate = maxBitrate;
                if (bitrate < minBitrate) bitrate = minBitrate;
                bitrateChanges.add(new BitrateChange(bitrate, System.currentTimeMillis()));

                streamer.setBitrateAndCompression(bitrate, compressionSettings);
            }
            lastChange = bitrateChange;
            bitrateChange = 0;

            // Wait between iterations to prevent jittery changes
            try { Thread.sleep(iterationLength); } catch (InterruptedException ignored) {}

            // Bitrate Change Diagnostics
            RuleStreamIssue();
            if (RuleRevertBitrate(lastChange, lastBufferPercent[0], lastBufferLength[0]))
                { bitrateChange = -lastChange; continue; }
            RuleSteadyBitrate();

            // Bitrate Adjustments
            if (RulePercentBufferingDecrease(lastBufferPercent)) { bitrateChange--; continue; }
            if (RulePercentBufferingIncrease(lastBufferPercent)) { bitrateChange++; continue; }
            if (RuleBufferLengthDecrease(lastBufferLength)) { bitrateChange--; continue; }
            if (RuleBufferLengthIncrease(lastBufferLength)) { bitrateChange++; continue; }
            if (RuleNetworkCongestionDecrease()) { bitrateChange--; continue; }

            // Bitrate Stability Diagnostics
            RuleSetBaselineBitrate();
            RuleLogStableSession();
        }
    }

    public void End() {
        isRunning = false;
        try {
            if (loopThread != null) loopThread.join();
        } catch (InterruptedException ignored) {}
    }
}

package src;
import java.util.Iterator;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Vector;

class BitrateChange {
    int bitrate;
    long timestamp;
    BitrateChange(int bitrate, long timestamp)
    { this.bitrate = bitrate; this.timestamp = timestamp; }
}

public class ExpertSystem {
    private VideoStreamer streamer;
    private String compressionSettings = "medium";

    // Custom variables
    float percentageX = 0.25F; // Rule 9, 1, 2, 7
    int changesX = 5; // Rule 4
    int consecutiveIntervalsY = 5; // Rule 2
    int secondsX = 3; // Rule 5 // in seconds
    int secondsY = 3; // Rule 6 // in seconds
    int minutesY = 3; // Rule 8, 10 // in minutes


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
        // Get clients
        Vector<Client> clients = streamer.getClients();

        int buffering = 0;

        // Find out how many clients are buffering
        for(Client client : clients) {
            if(client.bufferDuration > 0) {
                buffering++;
            }
        }

        // If min level bitrate and buffering still persists, pause stream and alert of technical issues
        if(bitrate == minBitrate && buffering >= (clients.size() * percentageX)) {
            // pause stream

            // print alert of technical issues
            System.out.println("ALERT: Stream was paused.");
        }
    }

    private boolean RuleRevertBitrate(int lastChange, float lastBufferPercent, int lastBufferLength) { // Rule 3
        // takes in lastChange, which is either -1, 0, +1
        // representing -1 increment, +1, etc.

        // Check whether bitrate was changed
        if(lastChange == 0) {
            return false;
        }

        // Get clients
        Vector<Client> clients = streamer.getClients();

        // Get number of clients buffering and total buffer length
        int buffering = 0;
        int totalBufferLength = 0;
        for(Client client : clients) {
            if(client.bufferDuration > 0) {
                buffering++;
            }

            totalBufferLength += client.bufferDuration;
        }

        // If no clients are buffering, return false
        if(buffering == 0) {
            return false;
        }

        // Calculate percent of clients buffering and the average buffer length
        float currBufferPercent = (float) buffering / clients.size();
        int avgBufferLength = totalBufferLength / clients.size();

        // If either increased compared to lastBufferPercent and lastBufferLength, return true (loop will revert)
        if(avgBufferLength > lastBufferLength || currBufferPercent > lastBufferPercent) {
            return true;
        }

        return false;
    }

    private boolean RuleSteadyBitrate() { // Rule 4
        // The queue used for this is bitrateChanges
        // .poll() pops front, .peek() returns front, .size() gives size
        // The queue stores bitrateChanges (see top of file), which has two items:
            // .bitrate = the bitrate in kbps (or whatever unit everywhere else uses)
            // .timestamp = millisecond system clock timestamp (same format returned by System.currentTimeMillis())

        // Define time window (i.e., 1 min rolling count of bitrate changes)
        int timeWindow = 60; // 60 sec

        // If timestamp is outside timeWindow, remove from queue
            // Check front of queue to see if it's too old, if it is then remove it and repeat
            // If it's not, then everything after it is younger and fine
        while(true) {
            // check that queue is not empty
            if(bitrateChanges.peek() == null) {
                break;
            }

            if (!((System.currentTimeMillis() - bitrateChanges.peek().timestamp) > timeWindow)) {
                break;
            }

            bitrateChanges.poll();
        }

        // If more than X items in queue (q.size()), bitrate is changing too rapidly so set it baseline (baselineBitrate)
        if(bitrateChanges.size() > changesX) {
            // If baselineBitrate exists, set bitrate to that
            if(baselineBitrate != 0) {
                this.bitrate = baselineBitrate;
            } else { // Otherwise, set bitrate to smallest bitrate in queue
                Iterator<BitrateChange> it = bitrateChanges.iterator();
                int smallestBitrate = bitrateChanges.peek().bitrate;

                // Loop through queue to get smallestBitrate
                while(it.hasNext()) {
                    BitrateChange change = it.next();

                    if(change.bitrate < smallestBitrate) {
                        smallestBitrate = change.bitrate;
                    }
                }

                this.bitrate = smallestBitrate;
            }

            return true;
        }

        return false;
    }


    // Bitrate Adjustments
    private boolean RulePercentBufferingDecrease(float[] bufferPercent) { // Rule 1
        Vector<Client> clients = streamer.getClients();

        // Loop through clients
        int buffering = 0;
        for(Client client : clients) {
            if(client.bufferDuration > 0) {
                buffering++;
            }
        }

        // Store % in bufferPercent[0], regardless if true or not
        float percent = (float) buffering / clients.size();
        if(bufferPercent[0] != -1) {
            bufferPercent[0] = percent;
        }

        // If >=X% of them are currently buffering, decrease bitrate (return true)
        if(percent >= percentageX) {
            return true;
        }

        return false;
    }

    private boolean RulePercentBufferingIncrease(float[] bufferPercent) { // Rule 2
        Vector<Client> clients = streamer.getClients();

        // Loop through clients
        int buffering = 0;
        for(Client client : clients) {
            if(client.bufferDuration > 0) {
                buffering++;
            }
        }

        // Store % in bufferPercent[0], regardless if true or not
        float percent = (float) buffering / clients.size();
        if(bufferPercent[0] != -1) {
            bufferPercent[0] = percent;
        }

        // If <X% of them are currently buffering, increase bitrate (return true)
        if(percent < percentageX) {
            // Increase consecutiveBufferFree by 1
            consecutiveBufferFree++;

            // If this happens for Y consecutive intervals, increase bitrate
            if(consecutiveBufferFree > consecutiveIntervalsY) {
                return true;
            }
        }

        return false;
    }

    private boolean RuleBufferLengthDecrease(int[] bufferLength) { // Rule 5
        // Get clients
        Vector<Client> clients = streamer.getClients();

        // Get total buffer length
        int totalBufferLength = 0;
        for(Client client : clients) {
            totalBufferLength += client.bufferDuration;
        }

        // Calculate average buffer length
        int avgBufferLength = totalBufferLength / clients.size();

        // Store avgBufferLength in bufferLength[0], regardless if true or not
        if(bufferLength[0] != -1) {
            bufferLength[0] = avgBufferLength;
        }

        // convert from sec to ms
        long ms = (long) secondsX * 60;

        // if < X seconds, decrease bitrate (return true)
        if(avgBufferLength < ms) {
            return true;
        }

        return false;
    }

    private boolean RuleBufferLengthIncrease(int[] bufferLength) { // Rule 6
        // Get clients
        Vector<Client> clients = streamer.getClients();

        // Get total buffer length
        int totalBufferLength = 0;
        for(Client client : clients) {
            totalBufferLength += client.bufferDuration;
        }

        // Calculate average buffer length
        int avgBufferLength = totalBufferLength / clients.size();

        // Store avgBufferLength in bufferLength[0], regardless if true or not
        if(bufferLength[0] != -1) {
            bufferLength[0] = avgBufferLength;
        }

        // convert from sec to ms
        long ms = (long) secondsY * 60;

        // if < X seconds, decrease bitrate (return true)
        if(avgBufferLength > ms) {
            return true;
        }

        return false;
    }

    private boolean RuleNetworkCongestionDecrease() { // Rule 7
        // Get clients
        Vector<Client> clients = streamer.getClients();

        // Get number of clients missing feedback
        int missingFeedback = 0;
        for(Client client : clients) {
            if(client.isUpdated == false) {
                missingFeedback++;
            }
        }

        // Calculate percentage of clients missing feedback
        float missingPercentage = missingFeedback / clients.size();

        // If more than X% are read as false (unupdated),
        // Then reduce bitrate bc of possible congestion
        if(missingPercentage > percentageX) {
            return true;
        }

        return false;
    }


    // Current Bitrate Stability Diagnostics
    private void RuleSetBaselineBitrate() { // Rule 8
        // Get clients
        Vector<Client> clients = streamer.getClients();

        // See if any client is buffering
        for(Client client : clients) {
            // If anyone is buffering, set lastBuffer timestamp to now (global var)
            if(client.bufferDuration > 0) {
                this.lastBuffer = System.currentTimeMillis();
            }
        }

        // convert from minutes to ms
        long ms = (long) minutesY * 60 * 1000;

        // Check if currentTime - lastBuffer > Y minutes
        if((System.currentTimeMillis() - lastBuffer) > ms) {
            // If it is, set current bitrate as baseline (baselineBitrate)
            this.baselineBitrate = bitrate;
        }
    }

    private void RuleLogStableSession() { // Rule 10
        // Get clients
        Vector<Client> clients = streamer.getClients();

        // See if any client is buffering
        for(Client client : clients) {
            // If anyone is buffering, set lastBuffer timestamp to now (global var)
            if(client.bufferDuration > 0) {
                this.lastBuffer = System.currentTimeMillis();
            }
        }

        // convert from minutes to ms
        long ms = (long) minutesY * 60 * 1000;

        // Check if currentTime - lastBuffer > Y minutes
        if((System.currentTimeMillis() - lastBuffer) > ms) {
            // If true, log session as "stable"
            System.out.println("STABLE SESSION");
        }
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

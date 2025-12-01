package src;

import java.util.List;

public class Main {

    public static void main(String[] args) {

        // Change the video path here ... can be mkv or mp4
        String videoPath = "/Users/ajrumore/Desktop/run.mkv";

        int initialBitrate     = 500;
        int minBitrate         = 500;
        int maxBitrate         = 2000;
        int bitrateIncrement   = 500;
        int iterationLengthMs  = 5000;

        VideoStreamer streamer = new VideoStreamer(videoPath);

        // Change this to 127.0.0., 1, 1 to test on localhost
        VlcMonitor monitor = new VlcMonitor("10.0.30.", 1, 50, streamer);

        ExpertSystem expert = new ExpertSystem(
                streamer,
                initialBitrate,
                minBitrate,
                maxBitrate,
                bitrateIncrement,
                iterationLengthMs
        );

        streamer.start();
        monitor.start();
        expert.Start();
    }

    // ----------------------------------------------------------------------
    // Pretty-print VLC client performance each cycle
    // ----------------------------------------------------------------------
    public static void printClientStats(List<Client> clients) {

        if (clients.isEmpty()) {
            System.out.println("No clients connected.\n");
            return;
        }

        System.out.println("----- Client Performance -----");

        for (Client c : clients) {

            System.out.println("Client " + c.getIpAddress());
            System.out.println("  Buffering: " + c.isBuffering());
            System.out.println("  Cache: " + c.getCachePercent() + "%");
            System.out.println("  Drops: " + c.getDroppedFrames());
            System.out.println("  Drop Δ: " + c.getDroppedDelta());
            System.out.println("  Healthy: " + c.isHealthy());
            System.out.println("  Struggling: " + c.isStruggling());

            System.out.println();
        }
    }
}

package src;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Vector;

public class VideoStreamer {

    private final String inputFile;
    private final String multicastAddress = "230.0.0.0";
    private final int multicastPort = 4446;

    private int bitrateKbps = 5000;

    private Process ffmpegProcess;
    private boolean streaming = false;

    private long startMs = 0;
    private long offsetMs = 0;

    private final Vector<Client> clients = new Vector<>();

    public VideoStreamer(String inputFile) {
        this.inputFile = inputFile;
    }

    // -------------------------
    // Client management
    // -------------------------
    public void addClient(Client c) {
        if (!clients.contains(c)) clients.add(c);
    }

    public void removeClient(Client c) {
        clients.remove(c);
    }

    public Vector<Client> getClients() {
        return clients;
    }

    // -------------------------
    // Control
    // -------------------------
    public void start() {
        if (streaming) return;
        launch();
    }

    public void stop() {
        streaming = false;
        if (ffmpegProcess != null) {
            ffmpegProcess.destroyForcibly();
        }
        ffmpegProcess = null;
    }

    public void setBitrate(int kbps) {
        if (kbps == bitrateKbps) return;
        bitrateKbps = kbps;
        if (streaming) restart();
    }

    public int getBitrate() {
        return bitrateKbps;
    }

    private void restart() {

        long now = System.currentTimeMillis();
        offsetMs += (now - startMs);

        if (ffmpegProcess != null && ffmpegProcess.isAlive())
            ffmpegProcess.destroyForcibly();

        launch();
    }

    private void launch() {
        try {
            long seekSeconds = offsetMs / 1000;

            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-hide_banner",
                    "-loglevel", "warning",
                    "-re",
                    "-ss", String.valueOf(seekSeconds),
                    "-i", inputFile,
                    "-target", "ntsc-dvd",
                    "-b:v", bitrateKbps + "k",
                    "-f", "mpegts",
                    "udp://" + multicastAddress + ":" + multicastPort + "?pkt_size=1316"
            );

            pb.redirectErrorStream(true);
            ffmpegProcess = pb.start();
            streaming = true;
            startMs = System.currentTimeMillis();

            System.out.println("[STREAM] FFmpeg started at " + bitrateKbps + " kbps");

            new Thread(() -> readFFmpeg(ffmpegProcess), "FFmpegReader").start();

        } catch (IOException e) {
            System.err.println("Failed to launch FFmpeg: " + e.getMessage());
            streaming = false;
        }
    }

    private void readFFmpeg(Process p) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {

            String line;
            while ((line = br.readLine()) != null) {
                // Only show startup and warnings due to -loglevel warning
                System.out.println("[FFMPEG] " + line);
            }

        } catch (IOException e) {
            System.err.println("FFmpeg reader exception: " + e.getMessage());
        }
    }
}

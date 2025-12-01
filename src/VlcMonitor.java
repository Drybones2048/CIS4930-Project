package src;

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * VlcMonitor discovers VLC RC instances on the subnet, keeps TCP connections
 * open, and actively polls them with the "stats" command. It also parses
 * passive log lines like "buffering 55%" and "cache: 42%" when VLC prints them.
 *
 * Client objects are created and registered with VideoStreamer when a new VLC
 * instance is detected, and removed when the RC socket closes.
 */
public class VlcMonitor {

    private final String subnetPrefix;
    private final int startHost;
    private final int endHost;
    private final int port = 5050;

    // Scan for new VLC instances every 5 seconds
    private final int scanIntervalMs = 5000;
    // Stats polling interval – aligned with ExpertSystem's typical iteration
    private final int statsIntervalMs = 5000;

    private final VideoStreamer streamer;

    private volatile boolean running = false;

    // Per-client connection state keyed by IP address
    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);

    public VlcMonitor(String subnetPrefix, int startHost, int endHost, VideoStreamer streamer) {
        this.subnetPrefix = subnetPrefix;
        this.startHost = startHost;
        this.endHost = endHost;
        this.streamer = streamer;
    }

    public void start() {
        running = true;

        Thread scanThread = new Thread(this::scanLoop, "VlcScan");
        scanThread.setDaemon(true);
        scanThread.start();

        System.out.println("[VLC] Monitor scanning " +
                subnetPrefix + startHost + "-" + endHost);
    }

    public void stop() {
        running = false;

        for (ClientConnection conn : connections.values()) {
            conn.close();
        }
        connections.clear();

        scheduler.shutdownNow();
    }

    // ----------------------------------------------------------------------
    // Discovery
    // ----------------------------------------------------------------------
    private void scanLoop() {
        while (running) {
            for (int host = startHost; host <= endHost; host++) {
                String ip = subnetPrefix + host;
                checkHost(ip);
            }

            try {
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void checkHost(String ip) {
        ClientConnection existing = connections.get(ip);
        if (existing != null) {
            if (!existing.isAlive()) {
                removeConnection(ip);
            }
            return;
        }

        // Try to connect to VLC RC
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), 150);

            System.out.println("[VLC] Connected: " + ip);

            Client client = new Client(ip);
            streamer.addClient(client);

            ClientConnection conn = new ClientConnection(ip, socket, client);
            connections.put(ip, conn);

            conn.start();

        } catch (IOException ignored) {
            // No VLC at this IP:port
        }
    }

    private void removeConnection(String ip) {
        ClientConnection conn = connections.remove(ip);
        if (conn != null) {
            System.out.println("[VLC] Removing client " + ip);
            conn.close();
            streamer.removeClient(conn.client);
        }
    }

    // ----------------------------------------------------------------------
    // Per-client connection
    // ----------------------------------------------------------------------
    private class ClientConnection {
        final String ip;
        final Socket socket;
        final Client client;

        private BufferedReader reader;
        private BufferedWriter writer;

        private volatile boolean closed = false;

        ClientConnection(String ip, Socket socket, Client client) {
            this.ip = ip;
            this.socket = socket;
            this.client = client;
        }

        void start() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            } catch (IOException e) {
                close();
                return;
            }

            // Reader thread: handles both passive VLC logs and "stats" output
            Thread t = new Thread(this::readLoop, "VlcRC-" + ip);
            t.setDaemon(true);
            t.start();

            // Stats polling: request "stats" every statsIntervalMs
            scheduler.scheduleAtFixedRate(this::sendStatsCommand,
                    0, statsIntervalMs, TimeUnit.MILLISECONDS);
        }

        boolean isAlive() {
            return !closed && socket.isConnected() && !socket.isClosed();
        }

        void close() {
            closed = true;
            try {
                if (reader != null) reader.close();
            } catch (IOException ignored) {
            }
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private void sendStatsCommand() {
            if (closed) return;
            try {
                synchronized (this) {
                    writer.write("stats\n");
                    writer.flush();
                }
            } catch (IOException e) {
                // Treat this as a disconnect
                removeConnection(ip);
            }
        }

        private void readLoop() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {

                    // Heartbeat on any line received
                    client.markUpdated();
                    System.out.println("[VLC " + ip + "] " + line);

                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;

                    // First go through passive telemetry
                    if (parsePassive(trimmed)) continue;

                    // Then stats-based telemetry
                    parseStats(trimmed);
                }
            } catch (IOException ignored) {
            }

            removeConnection(ip);
        }

        // Parses lines like "buffering 54%", "cache: 42%", "drop: 3", "playing"
        private boolean parsePassive(String line) {

            if (line.contains("buffering")) {
                client.setIsBuffering(true);
                return true;
            }

            if (line.startsWith("cache:")) {
                int percent = parsePercent(line);
                client.setCachePercent(percent);
                return true;
            }

            if (line.startsWith("drop:")) {
                int drops = parseInt(line);
                client.setDroppedFrames(drops);
                return true;
            }

            if (line.contains("playing")) {
                client.setIsBuffering(false);
                return true;
            }

            return false;
        }

        // Parses relevant lines from "stats" output
        private void parseStats(String line) {

            // Typical stats output has sections like:
            // input bitrate       : 1300 kb/s
            // lost packets        : 0
            // lost pictures       : 4

            if (line.startsWith("input bitrate")) {
                int bitrate = parseInt(line);
                // If input bitrate is zero for stats, treat that as buffering
                if (bitrate <= 0) {
                    client.setIsBuffering(true);
                    client.setCachePercent(0);
                } else {
                    client.setIsBuffering(false);
                    client.setCachePercent(100);
                }
                return;
            }

            if (line.startsWith("lost pictures")) {
                int lost = parseInt(line);
                client.setDroppedFrames(lost);
                return;
            }
        }

        private int parsePercent(String s) {
            try {
                String num = s.replaceAll("\\D+", "");
                if (num.isEmpty()) return 0;
                return Integer.parseInt(num);
            } catch (Exception e) {
                return 0;
            }
        }

        private int parseInt(String s) {
            try {
                String num = s.replaceAll("\\D+", "");
                if (num.isEmpty()) return 0;
                return Integer.parseInt(num);
            } catch (Exception e) {
                return 0;
            }
        }
    }
}

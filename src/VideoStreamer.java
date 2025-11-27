package src;
import java.net.MulticastSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.util.Vector;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.Vector;
import javax.imageio.ImageIO;

public class VideoStreamer {
    private Vector<Client> clients; // src.Client management

    //Video properties
    private String videoFilePath;
    private int currentBitrate;
    private String compressionSettings;

    //Streaming components
    private MulticastSocket multicastSocket;
    private InetAddress multicastGroup;
    private int multicastPort;

    //Video encoder/decoder
    private FrameGrab frameGrab;
    private boolean isStreaming;
    private int frameRate;

    private Thread streamingThread; //Threading

    // Constants to be referenced throughout the code
    private final int MAX_PACKET_SIZE = 1400; // Was listed as a safe UDP packet size
    private final int DEFAULT_FRAME_RATE = 30;

    public VideoStreamer(String multicastAddress, int port){ // Constructor for the class
        try {
            this.multicastPort = port;
            this.multicastGroup = InetAddress.getByName(multicastAddress);
            this.multicastSocket = new MulticastSocket(port);

            this.multicastSocket.joinGroup(multicastGroup); // Joins the multicast group

            this.clients = new Vector<Client>();
            this.isStreaming = false;
            this.currentBitrate = 500; // Default set to 500 kbps
            this.compressionSettings = "medium";
            this.frameRate = DEFAULT_FRAME_RATE;

            System.out.println("VideoStreamer initialized on " + multicastAddress + ":" + port);

        } catch (Exception e) {
            System.err.println("Error initializing VideoStreamer: " + e.getMessage());

            e.printStackTrace();
        }
    }

    public void loadVideo(String filePath){ // Loads and prepares video file for streaming
        try { // Creates a new video file, checks if the location is valid, then print message
            this.videoFilePath = filePath;
            File videoFile = new File(filePath);

            if(!videoFile.exists()){
                throw new FileNotFoundException("Video file not found: " + filePath);
            }

            this.frameGrab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(videoFile));

            System.out.println("Video loaded successfully: " + filePath);

        } catch (IOException | JCodecException e) { // Sends error if it did not work
            System.err.println("Error loading video: " + e.getMessage());

            e.printStackTrace();
        }
    }

    public synchronized void setBitrateAndCompression(int bitrate, String compression){ // Compress bitstream based on settings from Expert system
        this.currentBitrate = bitrate;
        this.compressionSettings = compression;

        System.out.println("Bitrate updated to: " + bitrate + " kbps, Compression: " + compression);
    }

    public synchronized void updateClientList(Vector<Client> clientList){ // Updates the client list
        this.clients = clientList;

        System.out.println("src.Client list updated. Active clients: " + clients.size());
    }

    public void startStream(){ // Start streaming to registered clients
        if(isStreaming){ // Checks for if the stream is already up and active
            System.out.println("Stream already running!");

            return;
        }

        if(videoFilePath == null || frameGrab == null){
            System.err.println("No video loaded. Call loadVideo() first.");

            return;
        }

        isStreaming = true;

        streamingThread = new Thread(() -> {
            try {
                streamVideo();
            } catch (Exception e){
                System.err.println("Streaming error: " + e.getMessage());

                e.printStackTrace();
            }
        });

        streamingThread.start();
        System.out.println("Video streaming started...");
    }

    private void streamVideo(){ // Main function of the program
        try{
            int frameCount = 0;
            long frameDelay = 1000 / frameRate; // milliseconds between frames

            frameGrab.seekToFramePrecise(0); // Reset frame grabber to beginning

            while(isStreaming){
                Picture picture = frameGrab.getNativeFrame(); // Grab next frame

                if(picture == null){ // End of video has been reached
                    System.out.println("End of video reached. Looping...");

                    frameGrab.seekToFramePrecise(0);

                    continue;
                }

                BufferedImage image = AWTUtil.toBufferedImage(picture); // Convert frame to BufferedImage

                byte[] compressedFrame = compressFrame(image); // Compress frame based on current settings

                sendPacket(compressedFrame, frameCount); // Send frame via multicast

                frameCount++;

                Thread.sleep(frameDelay); // Control frame rate

            }
        } catch (Exception e){
            System.err.println("Error in streaming loop: " + e.getMessage());

            e.printStackTrace();
        }
    }

    private byte[] compressFrame(BufferedImage frame) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        float quality = getCompressionQuality(); // Apply compression quality based on settings

        ImageIO.write(frame, "jpg", baos);

        return baos.toByteArray();
    }

    private float getCompressionQuality(){
        float baseQuality; // Map bitrate to quality since higher bitrate means higher quality

        if(currentBitrate >= 1000){
            baseQuality = 0.9f;
        } else if(currentBitrate >= 500){
            baseQuality = 0.7f;
        } else {
            baseQuality = 0.5f;
        }

        switch (compressionSettings.toLowerCase()){ // Adjust based on set compression setting
            case "low":
                return Math.min(1.0f, baseQuality + 0.1f);
            case "high":
                return Math.max(0.3f, baseQuality - 0.2f);
            default: // medium
                return baseQuality;
        }
    }

    private void sendPacket(byte[] data, int frameNumber){ // Send video packet to all clients via multicast
        try{
            if(data.length > MAX_PACKET_SIZE){
                sendFragmentedPacket(data, frameNumber);
            } else{
                DatagramPacket packet = new DatagramPacket(data, data.length, multicastGroup, multicastPort);

                multicastSocket.send(packet);
            }
        } catch (Exception e) {
            System.err.println("Error sending packet: " + e.getMessage());
        }
    }

    private void sendFragmentedPacket(byte[] data, int frameNumber) throws IOException{ // Send large frames as multiple fragmented packets
        int totalFragments = (int) Math.ceil((double) data.length / MAX_PACKET_SIZE);

        for (int i = 0; i < totalFragments; i++){
            int offset = i * MAX_PACKET_SIZE;
            int length = Math.min(MAX_PACKET_SIZE, data.length - offset);

            ByteArrayOutputStream headerStream = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(headerStream);
            dos.writeInt(frameNumber);
            dos.writeInt(i); // Fragment index
            dos.writeInt(totalFragments);

            // Combine header and data
            byte[] header = headerStream.toByteArray();
            byte[] fragment = new byte[header.length + length];
            System.arraycopy(header, 0, fragment, 0, header.length);
            System.arraycopy(data, offset, fragment, header.length, length);

            DatagramPacket packet = new DatagramPacket(fragment, fragment.length, multicastGroup, multicastPort);

            multicastSocket.send(packet);
        }
    }

    public void stopStream(){ // Stop streaming
        isStreaming = false;

        if(streamingThread != null && streamingThread.isAlive()){
            try{
                streamingThread.join(2000); // Wait up to 2 seconds
            } catch (InterruptedException e){
                System.err.println("Error stopping stream: " + e.getMessage());
            }
        }

        System.out.println("Video streaming stopped.");
    }

    public void cleanup(){ // Clean up resources
        stopStream();

        try{
            if(multicastSocket != null){
                multicastSocket.leaveGroup(multicastGroup);
                multicastSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }

    public boolean isStreaming(){ // Get current streaming status
        return isStreaming;
    }

    public int getCurrentBitrate(){ // Get the current bitrate level
        return currentBitrate;
    }

    public Vector<Client> getClients() { return clients; }

}

package src;

public class Client {
    private String ipAddress; // Client identification
    public int bufferDuration; // how long client is buffering in seconds or milliseconds, used to read average buffer length

    // This is used as a handshake to check if a client has stalled and not updated
    // When updating the client information (even if no changes are made), it should be set to true
    // When ExpertSystem reads the client's information every iteration (i.e., every 5 seconds), it sets it to false
    // It will know a client has stalled/is not responding if it reads a false isUpdated value.
    public boolean isUpdated;
}

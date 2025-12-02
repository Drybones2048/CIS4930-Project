# CIS4930-Project
Multimedia Expert Systems Term Project
Video Streaming with Multicast Packets

## VideoStreamer
Handles video compression and multicast streaming to clients with JCodec and MulticastSocket

## System Requirements

### Server Requirements
1. Java 21 JDK
2. FFmpeg installed and accessible via Terminal
3. IntelliJ IDEA (Recommended)

### Client Requirements
1. VLC Media Player installed and accessible via Terminal
2. Network connection to the server on the same subnet/VLAN

## Configuring and Running the Server

### Configuration
1. Adjust path to video file in Main
2. Set IP network address range of the subnet you are on
3. Adjust bitrate settings for expert system (Optional)

### Startup
1. Run Main in IntelliJ IDEA
2. Monitor performance in output console

## Launching Clients with VLC

1. Run one of the following commands below in your Terminal depending on your OS.
2. Replace `192.168.1.2` with the IP of your CLIENT system, making sure it's on the same subnet configured on the server above.

### Linux

```bash
vlc udp://@230.0.0.0:4446 \
    --extraintf rc \
    --rc-host 192.168.1.2:5050
```

### macOS

```bash
/Applications/VLC.app/Contents/MacOS/VLC udp://@230.0.0.0:4446 \
    --extraintf rc \
    --rc-host 192.168.1.2:5050
```


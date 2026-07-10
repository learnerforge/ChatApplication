package chat.client;

import java.io.*;
import java.net.*;

/**
 * Lightweight network wrapper that establishes a TCP socket connection
 * to the server and provides {@link #sendMessage} / {@link #receiveMessage}
 * convenience methods using {@link DataInputStream} / {@link DataOutputStream}.
 *
 * <p>This class is retained for the Swing desktop client.  The browser-based
 * client uses the native WebSocket API instead.</p>
 */
public class ChatClient {

    private Socket socket;
    private DataInputStream dis;
    private DataOutputStream dos;

    /**
     * Connects to the given server address and port.
     *
     * @param serverAddress hostname or IP
     * @param port          TCP port
     */
    public ChatClient(String serverAddress, int port) {
        try {
            socket = new Socket(serverAddress, port);
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** @return {@code true} if the socket is open and connected */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** Send a UTF-encoded string to the server. */
    public void sendMessage(String msg) {
        try {
            dos.writeUTF(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Block until a UTF-encoded string arrives from the server. */
    public String receiveMessage() {
        try {
            return dis.readUTF();
        } catch (Exception e) {
            return "";
        }
    }

    /** Close the underlying socket. */
    public void close() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (IOException ignored) { }
    }
}

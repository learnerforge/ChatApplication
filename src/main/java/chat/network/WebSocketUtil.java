package chat.network;

import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hand-rolled WebSocket protocol implementation built on top of raw
 * {@link Socket} streams.  This keeps the project dependency-free for
 * networking while still allowing browser clients to connect.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>HTTP → WebSocket upgrade handshake (RFC 6455)</li>
 *   <li>Text frame encoding (server → browser, unmasked)</li>
 *   <li>Text frame decoding (browser → server, masked)</li>
 * </ul>
 */
public final class WebSocketUtil {

    private static final Logger LOG = Logger.getLogger(WebSocketUtil.class.getName());
    private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketUtil() { }

    // ── Handshake ───────────────────────────────────────────

    /**
     * Performs the HTTP 101 WebSocket upgrade handshake.
     *
     * @param socket the freshly-accepted TCP socket
     * @return {@code true} if the handshake succeeded
     */
    public static boolean performHandshake(Socket socket) {
        return performHandshakeWithDetails(socket) != null;
    }

    /**
     * Performs handshake and returns details.
     * @return null if failed, ["WS"] if WebSocket, ["HTTP", path] if HTTP request
     */
    public static String[] performHandshakeWithDetails(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            OutputStream out = socket.getOutputStream();

            String line;
            String webSocketKey = null;
            String requestPath = "/";

            // Read the first line for the request path
            String firstLine = reader.readLine();
            if (firstLine == null) return null;
            String[] parts = firstLine.split(" ");
            if (parts.length >= 2) {
                requestPath = parts[1];
            }

            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    webSocketKey = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            if (webSocketKey == null) {
                LOG.fine("No Sec-WebSocket-Key — serving HTTP for: " + requestPath);
                return new String[]{"HTTP", requestPath};
            }

            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((webSocketKey + MAGIC_GUID).getBytes("UTF-8"));
            String acceptKey = Base64.getEncoder().encodeToString(hash);

            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey + "\r\n"
                    + "\r\n";

            out.write(response.getBytes("UTF-8"));
            out.flush();
            return new String[]{"WS"};

        } catch (NoSuchAlgorithmException e) {
            LOG.log(Level.SEVERE, "SHA-1 not available", e);
            return null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "WebSocket handshake failed", e);
            return null;
        }
    }

    /**
     * Load a classpath resource as bytes.
     */
    public static byte[] loadResource(String path) {
        try (InputStream is = WebSocketUtil.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to load resource: " + path, e);
            return null;
        }
    }

    // ── Write ───────────────────────────────────────────────

    /**
     * Encodes and sends a single UTF-8 text frame (server → browser, unmasked).
     *
     * @param out     the output stream of the socket
     * @param message the text to send
     * @throws IOException if the write fails
     */
    public static synchronized void sendText(OutputStream out, String message) throws IOException {
        byte[] payload = message.getBytes("UTF-8");
        int len = payload.length;

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + opcode 0x1 (text)

        if (len <= 125) {
            frame.write(len);
        } else if (len <= 65535) {
            frame.write(126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (len >> (8 * i)) & 0xFF);
            }
        }

        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    // ── Read ────────────────────────────────────────────────

    /**
     * Reads and decodes a single masked text frame from the browser.
     *
     * @param in the input stream of the socket
     * @return the decoded text, or {@code null} when the client closed the connection
     * @throws IOException if the read fails mid-frame
     */
    public static String readText(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 == -1) return null;

        int opcode = b1 & 0x0F;
        if (opcode == 0x8) return null; // close frame

        int b2 = in.read();
        if (b2 == -1) return null;

        boolean masked = (b2 & 0x80) != 0;
        int len = b2 & 0x7F;

        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            long longLen = 0;
            for (int i = 0; i < 8; i++) {
                longLen = (longLen << 8) | in.read();
            }
            len = (int) longLen;
        }

        byte[] maskKey = new byte[4];
        if (masked) {
            readFully(in, maskKey, 4);
        }

        byte[] payload = new byte[len];
        readFully(in, payload, len);

        if (masked) {
            for (int i = 0; i < len; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }

        return new String(payload, "UTF-8");
    }

    // ── Internal ────────────────────────────────────────────

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int r = in.read(buffer, read, length - read);
            if (r == -1) throw new IOException("Stream closed mid-frame");
            read += r;
        }
    }
}

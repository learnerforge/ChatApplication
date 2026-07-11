package chat.server;

import chat.config.Config;
import chat.database.DatabaseManager;
import chat.model.Message;
import chat.network.WebSocketUtil;

import java.io.*;
import java.net.*;
import javax.net.ssl.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.*;


/**
 * Entry point for the chat server.
 *
 * <p>Listens on the configured TCP port, performs WebSocket handshakes,
 * validates usernames, and spawns a {@link ClientHandler} thread per
 * connected client.  Clients are organised into {@link Room}s — each
 * room has its own user list and broadcast scope.</p>
 */
public class ChatServer {

    private static final Logger LOG = Logger.getLogger(ChatServer.class.getName());
    private static final AtomicLong messageCounter = new AtomicLong(0);

    static final int PORT = Config.PORT;

    /** All connected clients (for duplicate-username checks across rooms). */
    static final Vector<ClientHandler> allClients = new Vector<>();

    /** Available rooms, keyed by lower-case name. */
    static final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    /** Default room name. */
    static final String DEFAULT_ROOM = "General";

    private static ServerSocket serverSocket;
    private static ServerSocket sslServerSocket;
    private static FileHandler logFileHandler;

    // ── Entry point ─────────────────────────────────────────

    public static void main(String[] args) {
        setupLogging();
        LOG.info(Config.toSummary());

        DatabaseManager.initializeDatabase();
        initDefaultRooms();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received");
            try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
            catch (IOException ignored) { }
            try { if (sslServerSocket != null && !sslServerSocket.isClosed()) sslServerSocket.close(); }
            catch (IOException ignored) { }
            // Close log file handler to release server.log.lck
            if (logFileHandler != null) {
                logFileHandler.flush();
                logFileHandler.close();
            }
            // Shut down all client connections gracefully
            List<ClientHandler> clients;
            synchronized (allClients) {
                clients = new ArrayList<>(allClients);
            }
            for (ClientHandler c : clients) {
                try { c.socket.close(); } catch (IOException ignored) { }
            }
            LOG.info("Server stopped.");
        }));

        try {
            // Start plain TCP listener
            serverSocket = new ServerSocket(PORT, Config.BACKLOG, InetAddress.getByName(Config.HOST));
            LOG.info("Plain server started on " + Config.HOST + ":" + PORT);

            // Start SSL listener if enabled
            if (Config.SSL_ENABLED) {
                startSslListener();
            }

            // Accept connections on the plain socket
            acceptLoop(serverSocket, false);

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Server error", e);
        }
    }

    // ── SSL / TLS ───────────────────────────────────────────

    /** Start the SSL server socket on a separate port. */
    private static void startSslListener() throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            char[] ksPass = Config.SSL_KEYSTORE_PASSWORD.toCharArray();
            try (FileInputStream fis = new FileInputStream(Config.SSL_KEYSTORE)) {
                ks.load(fis, ksPass);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, ksPass);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            SSLServerSocketFactory ssf = ctx.getServerSocketFactory();
            sslServerSocket = ssf.createServerSocket(Config.SSL_PORT, Config.BACKLOG, InetAddress.getByName(Config.HOST));
            LOG.info("SSL server started on " + Config.HOST + ":" + Config.SSL_PORT);

            // Accept SSL connections in a separate thread
            new Thread(() -> acceptLoop(sslServerSocket, true), "ssl-accept").start();
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException
                 | UnrecoverableKeyException | KeyManagementException e) {
            throw new IOException("Failed to start SSL listener: " + e.getMessage(), e);
        }
    }

    /** Accept loop shared between plain and SSL sockets. */
    private static void acceptLoop(ServerSocket serverSocket, boolean isSsl) {
        try {
            while (true) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                handleClient(socket);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Accept loop ended (" + (isSsl ? "SSL" : "plain") + ")", e);
        }
    }

    /** Handle a single client connection: handshake, auth, dispatch. */
    private static void handleClient(Socket socket) {
        try {
            String[] handshakeResult = WebSocketUtil.performHandshakeWithDetails(socket);
            if (handshakeResult == null) {
                socket.close();
                return;
            }
            if (handshakeResult[0].equals("HTTP")) {
                serveHttpFile(socket, handshakeResult[1]);
                socket.close();
                return;
            }

            InputStream  in  = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // ── Authentication phase ──
            String authMsg = WebSocketUtil.readText(in);
            if (authMsg == null) { socket.close(); return; }

            String[] authParts = authMsg.split(":", 4);
            if (authParts.length < 2 || !authParts[0].equals("AUTH")) {
                WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid authentication message");
                socket.close();
                return;
            }

            String authType = authParts[1];
            String username = null;
            String rememberToken = null;

            if (authType.equals("LOGIN") && authParts.length == 4) {
                String loginUsername = authParts[2].trim();
                String password = authParts[3];
                chat.model.User user = DatabaseManager.loginUser(loginUsername, password);
                if (user != null) {
                    username = user.getUsername();
                    rememberToken = DatabaseManager.generateRememberToken(username);
                } else {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid username or password");
                    socket.close();
                    return;
                }
            } else if (authType.equals("REGISTER") && authParts.length == 4) {
                String regUsername = authParts[2].trim();
                String password = authParts[3];
                if (regUsername.isEmpty()) {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Username cannot be empty");
                    socket.close();
                    return;
                }
                if (password.length() < 4) {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Password must be at least 4 characters");
                    socket.close();
                    return;
                }
                if (DatabaseManager.registerUser(regUsername, password)) {
                    username = regUsername;
                    rememberToken = DatabaseManager.generateRememberToken(username);
                } else {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Username already taken");
                    socket.close();
                    return;
                }
            } else if (authType.equals("TOKEN") && authParts.length >= 3) {
                String token = authParts[2];
                chat.model.User user = DatabaseManager.validateRememberToken(token);
                if (user != null) {
                    username = user.getUsername();
                    rememberToken = DatabaseManager.generateRememberToken(username);
                } else {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid or expired token");
                    socket.close();
                    return;
                }
            } else {
                WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid authentication format");
                socket.close();
                return;
            }

            if (username == null || username.isEmpty()) {
                WebSocketUtil.sendText(out, "AUTH:ERROR:Authentication failed");
                socket.close();
                return;
            }

            if (isDuplicate(username)) {
                WebSocketUtil.sendText(out, "AUTH:ERROR:Username '" + username + "' already connected");
                socket.close();
                return;
            }

            WebSocketUtil.sendText(out, "AUTH:SUCCESS:" + rememberToken);

            ClientHandler client = new ClientHandler(socket, username, in, out);
            synchronized (allClients) {
                allClients.add(client);
            }

            Room defaultRoom = getOrCreateRoom(DEFAULT_ROOM);
            client.currentRoom = defaultRoom;
            defaultRoom.addClient(client);

            new Thread(client, "client-" + username).start();

            DatabaseManager.saveUser(username);
            sendRoomList(out);
            sendHistory(out, DEFAULT_ROOM);
            defaultRoom.broadcast(username + " joined the chat");
            defaultRoom.updateUserList();
            synchronized (allClients) {
                LOG.info(username + " joined " + DEFAULT_ROOM + " (online: " + allClients.size() + ")");
            }

        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to handle client connection", e);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    // ── Room management ─────────────────────────────────────

    private static void initDefaultRooms() {
        rooms.put("general", new Room("General", "General discussion"));
        rooms.put("programming", new Room("Programming", "Code, algorithms, and tech talk"));
        rooms.put("gaming", new Room("Gaming", "Games and entertainment"));
        rooms.put("music", new Room("Music", "Share and discuss music"));
        LOG.info("Initialised " + rooms.size() + " rooms");
    }

    /** Get or create a room by name. */
    static Room getOrCreateRoom(String name) {
        return rooms.computeIfAbsent(name.toLowerCase(), k -> new Room(name, ""));
    }

    /** Send the room list to a client. */
    static void sendRoomList(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder("ROOMS:");
        boolean first = true;
        for (Room room : rooms.values()) {
            if (!first) sb.append("|");
            sb.append(room.getName()).append(":").append(room.getSize());
            first = false;
        }
        WebSocketUtil.sendText(out, sb.toString());
    }

    /** Broadcast updated room list to ALL connected clients. */
    static void broadcastRoomList() {
        StringBuilder sb = new StringBuilder("ROOMS:");
        boolean first = true;
        for (Room room : rooms.values()) {
            if (!first) sb.append("|");
            sb.append(room.getName()).append(":").append(room.getSize());
            first = false;
        }
        String msg = sb.toString();
        List<ClientHandler> clientsSnapshot;
        synchronized (allClients) {
            clientsSnapshot = new ArrayList<>(allClients);
        }
        for (ClientHandler c : clientsSnapshot) {
            try { WebSocketUtil.sendText(c.out, msg); }
            catch (Exception ignored) { }
        }
    }

    // ── Client management ───────────────────────────────────

    /** Check for duplicate username across ALL rooms. */
    static boolean isDuplicate(String username) {
        synchronized (allClients) {
            for (ClientHandler c : allClients) {
                if (c.username.equalsIgnoreCase(username)) return true;
            }
        }
        return false;
    }

    /** Remove a client from everything on disconnect. */
    static void removeClient(ClientHandler handler) {
        synchronized (allClients) {
            allClients.remove(handler);
        }
        if (handler.currentRoom != null) {
            handler.currentRoom.removeClient(handler);
            handler.currentRoom.updateUserList();
        }
        broadcastRoomList();
    }

    /** Move a client from one room to another. */
    static void moveClient(ClientHandler client, Room newRoom) {
        if (client.currentRoom != null) {
            client.currentRoom.removeClient(client);
            client.currentRoom.broadcast(client.username + " left the chat");
            client.currentRoom.updateUserList();
        }
        client.currentRoom = newRoom;
        newRoom.addClient(client);
        newRoom.broadcast(client.username + " joined the chat");
        newRoom.updateUserList();
        broadcastRoomList();
    }

    static int getClientCount() {
        return allClients.size();
    }

    /** Generate a unique message ID. */
    static long nextMessageId() {
        return messageCounter.incrementAndGet();
    }

    // ── History ─────────────────────────────────────────────

    private static void serveHttpFile(Socket socket, String path) {
        try {
            OutputStream out = socket.getOutputStream();
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] html = WebSocketUtil.loadResource("/web/index.html");
                if (html != null) {
                    String header = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=UTF-8\r\n"
                        + "Content-Length: " + html.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                    out.write(header.getBytes("UTF-8"));
                    out.write(html);
                    out.flush();
                    return;
                }
            }
            String response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(response.getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to serve HTTP", e);
        }
    }

    private static void sendHistory(OutputStream out, String roomName) {
        try {
            List<Message> history = DatabaseManager.getRecentMessagesByRoom(roomName, Config.HISTORY_LIMIT);
            java.util.Map<Long, String> reactions = DatabaseManager.getReactionsForRoom(roomName);
            for (Message msg : history) {
                String formatted;
                if (msg.isSystem()) {
                    formatted = msg.getContent();
                } else if (msg.getTarget().isEmpty()) {
                    formatted = "[MSGID:" + msg.getId() + "] [" + msg.getTimestamp() + "] " + msg.getSender() + ": " + msg.getContent();
                    String r = reactions.get(msg.getId());
                    if (r != null && !r.isEmpty()) {
                        formatted += " [RX:" + r + "]";
                    }
                } else {
                    formatted = "[PRIVATE from " + msg.getSender() + "] " + msg.getContent();
                }
                WebSocketUtil.sendText(out, formatted);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send history", e);
        }
    }

    // ── Logging ─────────────────────────────────────────────

    private static void setupLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(new SimpleFormatter() {
            @Override
            public String format(LogRecord record) {
                return String.format("[%1$tT] [%2$-7s] %3$s%n",
                        new Date(record.getMillis()),
                        record.getLevel().getName(),
                        record.getMessage());
            }
        });
        root.addHandler(console);

        try {
            FileHandler fileHandler = new FileHandler("server.log", true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new SimpleFormatter());
            root.addHandler(fileHandler);
            logFileHandler = fileHandler;
        } catch (IOException e) {
            LOG.warning("Could not create log file: " + e.getMessage());
        }
    }
}

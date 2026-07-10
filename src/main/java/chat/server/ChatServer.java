package chat.server;

import chat.config.Config;
import chat.database.DatabaseManager;
import chat.model.Message;
import chat.network.WebSocketUtil;

import java.io.*;
import java.net.*;
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
            LOG.info("Server stopped.");
        }));

        try {
            serverSocket = new ServerSocket(PORT, Config.BACKLOG, InetAddress.getByName(Config.HOST));
            LOG.info("Server started on " + Config.HOST + ":" + PORT + " — waiting for connections");

            while (true) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);

                if (!WebSocketUtil.performHandshake(socket)) {
                    socket.close();
                    continue;
                }

                InputStream  in  = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                // ── Authentication phase ──
                String authMsg = WebSocketUtil.readText(in);
                if (authMsg == null) { socket.close(); continue; }

                String[] authParts = authMsg.split(":", 4);
                if (authParts.length < 2 || !authParts[0].equals("AUTH")) {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid authentication message");
                    socket.close();
                    continue;
                }

                String authType = authParts[1];
                String username = null;
                String rememberToken = null;

                if (authType.equals("LOGIN") && authParts.length == 4) {
                    // AUTH:LOGIN:username:password
                    String loginUsername = authParts[2].trim();
                    String password = authParts[3];
                    chat.model.User user = DatabaseManager.loginUser(loginUsername, password);
                    if (user != null) {
                        username = user.getUsername();
                        rememberToken = DatabaseManager.generateRememberToken(username);
                    } else {
                        WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid username or password");
                        socket.close();
                        continue;
                    }
                } else if (authType.equals("REGISTER") && authParts.length == 4) {
                    // AUTH:REGISTER:username:password
                    String regUsername = authParts[2].trim();
                    String password = authParts[3];
                    if (regUsername.isEmpty()) {
                        WebSocketUtil.sendText(out, "AUTH:ERROR:Username cannot be empty");
                        socket.close();
                        continue;
                    }
                    if (password.length() < 4) {
                        WebSocketUtil.sendText(out, "AUTH:ERROR:Password must be at least 4 characters");
                        socket.close();
                        continue;
                    }
                    if (DatabaseManager.registerUser(regUsername, password)) {
                        username = regUsername;
                        rememberToken = DatabaseManager.generateRememberToken(username);
                    } else {
                        WebSocketUtil.sendText(out, "AUTH:ERROR:Username already taken");
                        socket.close();
                        continue;
                    }
                } else if (authType.equals("TOKEN") && authParts.length >= 3) {
                    // AUTH:TOKEN:token
                    String token = authParts[2];
                    chat.model.User user = DatabaseManager.validateRememberToken(token);
                    if (user != null) {
                        username = user.getUsername();
                        rememberToken = DatabaseManager.generateRememberToken(username);
                    } else {
                        WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid or expired token");
                        socket.close();
                        continue;
                    }
                } else {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Invalid authentication format");
                    socket.close();
                    continue;
                }

                if (username == null || username.isEmpty()) {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Authentication failed");
                    socket.close();
                    continue;
                }

                if (isDuplicate(username)) {
                    WebSocketUtil.sendText(out, "AUTH:ERROR:Username '" + username + "' already connected");
                    socket.close();
                    continue;
                }

                WebSocketUtil.sendText(out, "AUTH:SUCCESS:" + rememberToken);

                ClientHandler client = new ClientHandler(socket, username, in, out);
                allClients.add(client);

                // Assign to default room
                Room defaultRoom = getOrCreateRoom(DEFAULT_ROOM);
                client.currentRoom = defaultRoom;
                defaultRoom.addClient(client);

                new Thread(client, "client-" + username).start();

                DatabaseManager.saveUser(username);
                sendRoomList(out);
                sendHistory(out, DEFAULT_ROOM);
                defaultRoom.broadcast(username + " joined the chat");
                defaultRoom.updateUserList();
                LOG.info(username + " joined " + DEFAULT_ROOM + " (online: " + allClients.size() + ")");
            }

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Server error", e);
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
        synchronized (allClients) {
            for (ClientHandler c : allClients) {
                try { WebSocketUtil.sendText(c.out, msg); }
                catch (Exception ignored) { }
            }
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
        allClients.remove(handler);
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

    private static void sendHistory(OutputStream out, String roomName) {
        try {
            List<Message> history = DatabaseManager.getRecentMessagesByRoom(roomName, Config.HISTORY_LIMIT);
            for (Message msg : history) {
                String formatted;
                if (msg.isSystem()) {
                    formatted = msg.getContent();
                } else if (msg.getTarget().isEmpty()) {
                    formatted = "[" + msg.getTimestamp() + "] " + msg.getSender() + ": " + msg.getContent();
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
        } catch (IOException e) {
            LOG.warning("Could not create log file: " + e.getMessage());
        }
    }
}

package chat.server;

import chat.database.DatabaseManager;
import chat.model.User;
import chat.network.WebSocketUtil;

import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-client thread that reads WebSocket frames from the browser,
 * routes public broadcasts, private messages, room switches, typing
 * indicators, and delivery/seen acknowledgements.
 */
public class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    /** Tracks who is currently typing in each room — key: roomName, value: set of usernames. */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> typingUsers = new ConcurrentHashMap<>();

    final Socket socket;
    volatile String username;
    final InputStream  in;
    final OutputStream out;

    volatile Room currentRoom;

    ClientHandler(Socket socket, String username, InputStream in, OutputStream out) {
        this.socket   = socket;
        this.username = username;
        this.in       = in;
        this.out      = out;
    }

    @Override
    public void run() {
        try {
            String message;
            while ((message = WebSocketUtil.readText(in)) != null) {
                handleMessage(message);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Read loop ended for " + username, e);
        } finally {
            clearTyping();
            ChatServer.removeClient(this);
            if (currentRoom != null) {
                currentRoom.broadcast(username + " left the chat");
                currentRoom.updateUserList();
            }
            try { socket.close(); } catch (IOException ignored) { }
            LOG.info(username + " disconnected (online: " + ChatServer.getClientCount() + ")");
        }
    }

    // ── Message routing ─────────────────────────────────────

    private void handleMessage(String message) throws IOException {
        // ── Typing indicators (check before general commands) ──
        if (message.equals("/typing start")) {
            startTyping();
            return;
        }
        if (message.equals("/typing stop")) {
            stopTyping();
            return;
        }

        // ── Seen acknowledgement ──
        if (message.startsWith("/seen ")) {
            handleSeen(message);
            return;
        }

        // ── Private message ──
        if (message.startsWith("@")) {
            handlePrivateMessage(message);
            return;
        }

        // ── Commands ──
        if (message.startsWith("/")) {
            handleCommand(message);
            return;
        }

        // ── Public message in current room ──
        long msgId = ChatServer.nextMessageId();
        String timestamp = new SimpleDateFormat("hh:mm a").format(new Date());
        String formatted = "[MSGID:" + msgId + "] [" + timestamp + "] " + username + ": " + message;

        if (currentRoom != null) {
            currentRoom.broadcast(formatted);
        }

        // Send message ID back to sender so they can track it
        WebSocketUtil.sendText(out, "MSG_ID:" + msgId);

        DatabaseManager.saveMessage(username, "", message, currentRoom != null ? currentRoom.getName() : "General");
    }

    // ── Commands ────────────────────────────────────────────

    private void handleCommand(String message) throws IOException {
        String[] parts = message.split(" ", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/rooms":
                ChatServer.sendRoomList(out);
                break;

            case "/join":
                if (parts.length < 2) {
                    WebSocketUtil.sendText(out, "Usage: /join RoomName");
                    break;
                }
                String roomName = parts[1].trim();
                Room newRoom = ChatServer.getOrCreateRoom(roomName);
                if (currentRoom == newRoom) {
                    WebSocketUtil.sendText(out, "You are already in " + newRoom.getName());
                    break;
                }
                ChatServer.moveClient(this, newRoom);
                WebSocketUtil.sendText(out, "ROOM:" + newRoom.getName());
                sendRoomHistory(newRoom.getName());
                LOG.info(username + " moved to " + newRoom.getName());
                break;

            case "/leave":
                if (currentRoom != null && !currentRoom.getName().equals(ChatServer.DEFAULT_ROOM)) {
                    Room general = ChatServer.getOrCreateRoom(ChatServer.DEFAULT_ROOM);
                    ChatServer.moveClient(this, general);
                    WebSocketUtil.sendText(out, "ROOM:" + general.getName());
                    sendRoomHistory(general.getName());
                } else {
                    WebSocketUtil.sendText(out, "You are already in the default room");
                }
                break;

            case "/roomslist":
                StringBuilder sb = new StringBuilder("ROOMS:");
                boolean first = true;
                for (Room r : ChatServer.rooms.values()) {
                    if (!first) sb.append("|");
                    sb.append(r.getName()).append(":").append(r.getSize());
                    first = false;
                }
                WebSocketUtil.sendText(out, sb.toString());
                break;

            // ── Account commands ──

            case "/logout":
                handleLogout();
                break;

            case "/changepassword":
                if (parts.length < 3) {
                    WebSocketUtil.sendText(out, "Usage: /changepassword <oldPassword> <newPassword>");
                    break;
                }
                handleChangePassword(parts[1], parts[2]);
                break;

            case "/changeusername":
                if (parts.length < 3) {
                    WebSocketUtil.sendText(out, "Usage: /changeusername <newUsername> <password>");
                    break;
                }
                handleChangeUsername(parts[1], parts[2]);
                break;

            case "/deleteaccount":
                if (parts.length < 2) {
                    WebSocketUtil.sendText(out, "Usage: /deleteaccount <password>");
                    break;
                }
                handleDeleteAccount(parts[1]);
                break;

            case "/deletemymessages":
                handleDeleteMyMessages();
                break;

            case "/clearroom":
                if (currentRoom != null) {
                    handleClearRoom();
                }
                break;

            // ── Message commands ──

            case "/search":
                if (parts.length < 2) {
                    WebSocketUtil.sendText(out, "Usage: /search <query>");
                    break;
                }
                handleSearch(parts[1]);
                break;

            case "/edit":
                if (parts.length < 3) {
                    WebSocketUtil.sendText(out, "Usage: /edit <messageId> <new text>");
                    break;
                }
                // parts[0] = "/edit", parts[1] = messageId, the rest is the new text
                int editSpaceIdx = message.indexOf(' ', message.indexOf(' ') + 1);
                if (editSpaceIdx > 0) {
                    handleEditMessage(parts[1], message.substring(editSpaceIdx + 1));
                } else {
                    WebSocketUtil.sendText(out, "Usage: /edit <messageId> <new text>");
                }
                break;

            case "/delmsg":
                if (parts.length < 2) {
                    WebSocketUtil.sendText(out, "Usage: /delmsg <messageId>");
                    break;
                }
                handleDeleteMessage(parts[1]);
                break;

            case "/react":
                if (parts.length < 3) {
                    WebSocketUtil.sendText(out, "Usage: /react <messageId> <emoji>");
                    break;
                }
                handleReaction(parts[1], parts[2]);
                break;

            default:
                WebSocketUtil.sendText(out, "Unknown command: " + cmd);
                break;
        }
    }

    // ── Account management ──

    private void handleLogout() throws IOException {
        DatabaseManager.clearRememberToken(username);
        WebSocketUtil.sendText(out, "LOGOUT:SUCCESS");
        socket.close();
    }

    private void handleChangePassword(String oldPassword, String newPassword) throws IOException {
        if (newPassword.length() < 4) {
            WebSocketUtil.sendText(out, "ACCT:ERROR:New password must be at least 4 characters");
            return;
        }
        if (DatabaseManager.changePassword(username, oldPassword, newPassword)) {
            WebSocketUtil.sendText(out, "ACCT:SUCCESS:Password changed successfully");
        } else {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Current password is incorrect");
        }
    }

    private void handleChangeUsername(String newUsername, String password) throws IOException {
        if (newUsername.isEmpty() || newUsername.length() < 2) {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Username must be at least 2 characters");
            return;
        }
        if (ChatServer.isDuplicate(newUsername)) {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Username '" + newUsername + "' is already taken");
            return;
        }
        if (DatabaseManager.changeUsername(username, newUsername, password)) {
            String oldName = username;
            username = newUsername;
            WebSocketUtil.sendText(out, "ACCT:USERNAME_CHANGED:" + newUsername);
            // Broadcast name change to room
            if (currentRoom != null) {
                currentRoom.broadcast(oldName + " is now known as " + newUsername);
                currentRoom.updateUserList();
            }
        } else {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Password is incorrect or username taken");
        }
    }

    private void handleDeleteAccount(String password) throws IOException {
        User user = DatabaseManager.loginUser(username, password);
        if (user == null) {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Password is incorrect");
            return;
        }
        // Notify room
        if (currentRoom != null) {
            currentRoom.broadcast(username + " deleted their account");
        }
        DatabaseManager.deleteAccount(username);
        WebSocketUtil.sendText(out, "ACCT:ACCOUNT_DELETED");
        socket.close();
    }

    private void handleDeleteMyMessages() throws IOException {
        int deleted = DatabaseManager.deleteUserMessages(username);
        WebSocketUtil.sendText(out, "ACCT:SUCCESS:Deleted " + deleted + " messages");
    }

    private void handleClearRoom() throws IOException {
        if (currentRoom == null) return;
        int deleted = DatabaseManager.deleteRoomMessages(currentRoom.getName());
        currentRoom.broadcast("Chat history cleared by " + username);
        WebSocketUtil.sendText(out, "ACCT:SUCCESS:Cleared " + deleted + " messages from " + currentRoom.getName());
    }

    // ── Message search ──

    private void handleSearch(String query) throws IOException {
        if (currentRoom == null) return;
        List<chat.model.Message> results = DatabaseManager.searchMessages(currentRoom.getName(), query, 20);
        if (results.isEmpty()) {
            WebSocketUtil.sendText(out, "SEARCH:NONE:No messages found for '" + query + "'");
            return;
        }
        StringBuilder sb = new StringBuilder("SEARCH:RESULTS:");
        boolean first = true;
        for (chat.model.Message msg : results) {
            if (!first) sb.append(";");
            sb.append(msg.getId()).append("~").append(msg.getSender())
              .append("~").append(msg.getContent().replace("~", " "))
              .append("~").append(msg.getTimestamp());
            first = false;
        }
        WebSocketUtil.sendText(out, sb.toString());
    }

    // ── Edit message ──

    private void handleEditMessage(String msgIdStr, String newContent) throws IOException {
        try {
            long msgId = Long.parseLong(msgIdStr.trim());
            if (DatabaseManager.editMessage(msgId, username, newContent)) {
                WebSocketUtil.sendText(out, "MSG_EDITED:" + msgId + "~" + newContent);
                if (currentRoom != null) {
                    currentRoom.broadcastExcept("MSG_EDITED:" + msgId + "~" + newContent, this);
                }
            } else {
                WebSocketUtil.sendText(out, "ACCT:ERROR:Cannot edit this message (not yours or not found)");
            }
        } catch (NumberFormatException e) {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Invalid message ID");
        }
    }

    // ── Delete single message ──

    private void handleDeleteMessage(String msgIdStr) throws IOException {
        try {
            long msgId = Long.parseLong(msgIdStr.trim());
            if (DatabaseManager.deleteMessage(msgId, username)) {
                WebSocketUtil.sendText(out, "MSG_DELETED:" + msgId);
                if (currentRoom != null) {
                    currentRoom.broadcastExcept("MSG_DELETED:" + msgId, this);
                }
            } else {
                WebSocketUtil.sendText(out, "ACCT:ERROR:Cannot delete this message (not yours or not found)");
            }
        } catch (NumberFormatException e) {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Invalid message ID");
        }
    }

    // ── Reactions ──

    private void handleReaction(String msgIdStr, String emoji) throws IOException {
        try {
            long msgId = Long.parseLong(msgIdStr.trim());
            String result = DatabaseManager.toggleReaction(msgId, username, emoji);
            String reactions = DatabaseManager.getReactions(msgId);
            String reactionMsg = "MSG_REACTION:" + msgId + "~" + reactions;
            if (currentRoom != null) {
                currentRoom.broadcast(reactionMsg);
            }
        } catch (NumberFormatException e) {
            WebSocketUtil.sendText(out, "ACCT:ERROR:Invalid message ID");
        }
    }

    // ── Private messages ────────────────────────────────────

    private void handlePrivateMessage(String message) throws IOException {
        int spaceIndex = message.indexOf(" ");
        if (spaceIndex == -1) {
            WebSocketUtil.sendText(out, "Usage: @username your message");
            return;
        }

        String targetUser    = message.substring(1, spaceIndex);
        String privateContent = message.substring(spaceIndex + 1);

        if (privateContent.isEmpty()) {
            WebSocketUtil.sendText(out, "Cannot send empty message");
            return;
        }

        long msgId = ChatServer.nextMessageId();

        // Search across ALL rooms for the target
        ClientHandler target = null;
        for (Room room : ChatServer.rooms.values()) {
            target = room.findUser(targetUser);
            if (target != null) break;
        }

        if (target != null) {
            String timestamp = new SimpleDateFormat("hh:mm a").format(new Date());
            WebSocketUtil.sendText(target.out, "[PRIVATE from " + username + "] " + privateContent);
            if (target != this) {
                WebSocketUtil.sendText(out, "[PRIVATE to " + targetUser + "] " + privateContent);
            }
            WebSocketUtil.sendText(out, "MSG_ID:" + msgId);
            DatabaseManager.saveMessage(username, targetUser, privateContent,
                    currentRoom != null ? currentRoom.getName() : "General");
        } else {
            WebSocketUtil.sendText(out, "User '" + targetUser + "' not found");
        }
    }

    // ── Typing indicators ───────────────────────────────────

    private void startTyping() {
        if (currentRoom == null) return;
        String roomName = currentRoom.getName();
        typingUsers.computeIfAbsent(roomName, k -> new ConcurrentHashMap<>()).put(username, System.currentTimeMillis());
        currentRoom.broadcastExcept("TYPING:" + username, this);
    }

    private void stopTyping() {
        if (currentRoom == null) return;
        String roomName = currentRoom.getName();
        ConcurrentHashMap<String, Long> roomTyping = typingUsers.get(roomName);
        if (roomTyping != null) {
            roomTyping.remove(username);
        }
        currentRoom.broadcastExcept("TYPING_STOP:" + username, this);
    }

    private void clearTyping() {
        if (currentRoom == null) return;
        String roomName = currentRoom.getName();
        ConcurrentHashMap<String, Long> roomTyping = typingUsers.get(roomName);
        if (roomTyping != null) {
            roomTyping.remove(username);
            if (roomTyping.isEmpty()) {
                typingUsers.remove(roomName, roomTyping);
            }
        }
        currentRoom.broadcastExcept("TYPING_STOP:" + username, this);
    }

    // ── Seen ────────────────────────────────────────────────

    private void handleSeen(String message) {
        try {
            String parts = message.substring(6); // "/seen "
            // Format: /seen messageId
            long messageId = Long.parseLong(parts.trim());
            // Broadcast seen status to all clients in room
            if (currentRoom != null) {
                currentRoom.broadcastExcept("SEEN:" + messageId + ":" + username, this);
            }
        } catch (NumberFormatException e) {
            // ignore malformed seen
        }
    }

    // ── Helpers ─────────────────────────────────────────────

    private void sendRoomHistory(String roomName) {
        try {
            java.util.List<chat.model.Message> history =
                DatabaseManager.getRecentMessagesByRoom(roomName, 30);
            Map<Long, String> reactions = DatabaseManager.getReactionsForRoom(roomName);
            for (chat.model.Message msg : history) {
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
            LOG.log(Level.WARNING, "Failed to send room history", e);
        }
    }
}

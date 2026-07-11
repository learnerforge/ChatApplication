package chat.database;

import chat.config.Config;
import chat.model.Message;
import chat.model.User;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistence layer backed by SQLite (portable, zero-config).
 * Supports room-scoped message history and user authentication.
 */
public final class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 64;
    private static final int ITERATIONS = 100000;

    private DatabaseManager() { }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(Config.DB_URL);
    }

    /** Creates tables if they do not already exist. */
    public static void initializeDatabase() {
        String usersTable = "CREATE TABLE IF NOT EXISTS users ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "username TEXT UNIQUE NOT NULL,"
                + "password_hash TEXT,"
                + "salt TEXT,"
                + "remember_token TEXT,"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP);";

        String messagesTable = "CREATE TABLE IF NOT EXISTS messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "sender TEXT NOT NULL,"
                + "target TEXT NOT NULL DEFAULT '',"
                + "content TEXT NOT NULL,"
                + "room TEXT NOT NULL DEFAULT 'General',"
                + "is_system INTEGER NOT NULL DEFAULT 0,"
                + "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP);";

        String reactionsTable = "CREATE TABLE IF NOT EXISTS reactions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "message_id INTEGER NOT NULL,"
                + "username TEXT NOT NULL,"
                + "emoji TEXT NOT NULL,"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE(message_id, username, emoji));";

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(usersTable);
            stmt.execute(messagesTable);
            stmt.execute(reactionsTable);

            // Migration: add room column if missing (for existing databases)
            try {
                stmt.executeQuery("SELECT room FROM messages LIMIT 1");
            } catch (SQLException e) {
                stmt.execute("ALTER TABLE messages ADD COLUMN room TEXT NOT NULL DEFAULT 'General'");
                LOG.info("Migrated messages table — added 'room' column");
            }

            // Migration: add password columns if missing
            try {
                stmt.executeQuery("SELECT password_hash FROM users LIMIT 1");
            } catch (SQLException e) {
                stmt.execute("ALTER TABLE users ADD COLUMN password_hash TEXT");
                stmt.execute("ALTER TABLE users ADD COLUMN salt TEXT");
                stmt.execute("ALTER TABLE users ADD COLUMN remember_token TEXT");
                LOG.info("Migrated users table — added auth columns");
            }

            LOG.info("Database tables initialised successfully");
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Database initialisation failed", e);
        }
    }

    // ── Message Features ─────────────────────────────────────

    /** Search messages in a room. */
    public static List<Message> searchMessages(String room, String query, int limit) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE room = ? AND content LIKE ? AND is_system = 0 ORDER BY id DESC LIMIT ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room);
            ps.setString(2, "%" + query + "%");
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                messages.add(new Message(
                        rs.getLong("id"),
                        rs.getString("sender"),
                        rs.getString("target"),
                        rs.getString("content"),
                        rs.getString("timestamp"),
                        rs.getInt("is_system") == 1
                ));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "searchMessages failed", e);
        }
        java.util.Collections.reverse(messages);
        return messages;
    }

    /** Edit a message. Returns true if the message was owned by the sender and updated. */
    public static boolean editMessage(long messageId, String sender, String newContent) {
        String sql = "UPDATE messages SET content = ? WHERE id = ? AND sender = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newContent);
            ps.setLong(2, messageId);
            ps.setString(3, sender);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "editMessage failed", e);
            return false;
        }
    }

    /** Get message sender by ID (for ownership checks). */
    public static String getMessageSender(long messageId) {
        String sql = "SELECT sender FROM messages WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("sender");
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "getMessageSender failed", e);
        }
        return null;
    }

    // ── Password Hashing ─────────────────────────────────────

    private static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private static String hashPassword(String password, String salt) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, ITERATIONS, HASH_LENGTH * 8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    private static boolean verifyPassword(String password, String storedHash, String salt) {
        String computedHash = hashPassword(password, salt);
        return computedHash.equals(storedHash);
    }

    // ── User Registration ────────────────────────────────────

    /**
     * Register a new user with username and password.
     * @return true if registration succeeded, false if username taken
     */
    public static boolean registerUser(String username, String password) {
        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        String sql = "INSERT INTO users (username, password_hash, salt) VALUES (?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "registerUser failed for " + username, e);
            return false;
        }
    }

    // ── User Login ───────────────────────────────────────────

    /**
     * Authenticate user with username and password.
     * @return User object if credentials valid, null otherwise
     */
    public static User loginUser(String username, String password) {
        String sql = "SELECT id, username, password_hash, salt, remember_token, created_at "
                   + "FROM users WHERE username = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password_hash");
                String salt = rs.getString("salt");

                // Legacy users without password
                if (storedHash == null || salt == null) {
                    return null;
                }

                if (verifyPassword(password, storedHash, salt)) {
                    return new User(
                        rs.getLong("id"),
                        rs.getString("username"),
                        storedHash,
                        salt,
                        rs.getString("remember_token"),
                        rs.getString("created_at")
                    );
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "loginUser failed for " + username, e);
        }
        return null;
    }

    // ── Remember Token ───────────────────────────────────────

    /**
     * Generate a new remember token for the user.
     * @return the token string
     */
    public static String generateRememberToken(String username) {
        String token = UUID.randomUUID().toString();
        String sql = "UPDATE users SET remember_token = ? WHERE username = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, username);
            ps.executeUpdate();
            return token;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "generateRememberToken failed for " + username, e);
            return null;
        }
    }

    /**
     * Validate a remember token and return the user.
     * @return User object if token valid, null otherwise
     */
    public static User validateRememberToken(String token) {
        String sql = "SELECT id, username, password_hash, salt, remember_token, created_at "
                   + "FROM users WHERE remember_token = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new User(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("salt"),
                    rs.getString("remember_token"),
                    rs.getString("created_at")
                );
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "validateRememberToken failed", e);
        }
        return null;
    }

    /**
     * Clear the remember token (logout).
     */
    public static void clearRememberToken(String username) {
        String sql = "UPDATE users SET remember_token = NULL WHERE username = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "clearRememberToken failed for " + username, e);
        }
    }

    // ── Legacy User Methods ──────────────────────────────────

    public static void saveUser(String username) {
        // Only save if user doesn't exist (for legacy guest logins)
        String checkSql = "SELECT COUNT(*) FROM users WHERE username = ?";
        String insertSql = "INSERT OR IGNORE INTO users (username) VALUES (?)";
        try (Connection conn = connect();
             PreparedStatement checkPs = conn.prepareStatement(checkSql)) {
            checkPs.setString(1, username);
            ResultSet rs = checkPs.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                    insertPs.setString(1, username);
                    insertPs.executeUpdate();
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "saveUser failed for " + username, e);
        }
    }

    public static void saveMessage(String sender, String target, String content, String room) {
        String sql = "INSERT INTO messages (sender, target, content, room) VALUES (?, ?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setString(2, target);
            ps.setString(3, content);
            ps.setString(4, room);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "saveMessage failed", e);
        }
    }

    public static void saveMessage(String sender, String target, String content) {
        saveMessage(sender, target, content, "General");
    }

    public static List<Message> getRecentMessagesByRoom(String room, int limit) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE room = ? ORDER BY id DESC LIMIT ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                messages.add(new Message(
                        rs.getLong("id"),
                        rs.getString("sender"),
                        rs.getString("target"),
                        rs.getString("content"),
                        rs.getString("timestamp"),
                        rs.getInt("is_system") == 1
                ));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "getRecentMessagesByRoom failed", e);
        }
        java.util.Collections.reverse(messages);
        return messages;
    }

    public static List<Message> getRecentMessages(int limit) {
        return getRecentMessagesByRoom("General", limit);
    }

    public static int getMessageCount() {
        String sql = "SELECT COUNT(*) FROM messages";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public static int getUserCount() {
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    // ── Account Management ───────────────────────────────────

    /**
     * Delete a user account and all their messages.
     * @return true if deletion succeeded
     */
    public static boolean deleteAccount(String username) {
        String deleteMessages = "DELETE FROM messages WHERE sender = ?";
        String deleteUser = "DELETE FROM users WHERE username = ?";
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps1 = conn.prepareStatement(deleteMessages);
                 PreparedStatement ps2 = conn.prepareStatement(deleteUser)) {
                ps1.setString(1, username);
                ps1.executeUpdate();
                ps2.setString(1, username);
                ps2.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "deleteAccount failed for " + username, e);
            return false;
        }
    }

    /**
     * Change user password.
     * @return true if password changed successfully
     */
    public static boolean changePassword(String username, String oldPassword, String newPassword) {
        // First verify old password
        User user = loginUser(username, oldPassword);
        if (user == null) {
            return false;
        }
        // Update with new password
        String salt = generateSalt();
        String hash = hashPassword(newPassword, salt);
        String sql = "UPDATE users SET password_hash = ?, salt = ? WHERE username = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.setString(3, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "changePassword failed for " + username, e);
            return false;
        }
    }

    /**
     * Change username.
     * @return true if username changed successfully
     */
    public static boolean changeUsername(String oldUsername, String newUsername, String password) {
        // Verify password first
        User user = loginUser(oldUsername, password);
        if (user == null) {
            return false;
        }
        // Check if new username is taken
        String checkSql = "SELECT COUNT(*) FROM users WHERE username = ?";
        String updateSql = "UPDATE users SET username = ? WHERE username = ?";
        String updateMessages = "UPDATE messages SET sender = ? WHERE sender = ?";
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                // Check uniqueness
                PreparedStatement checkPs = conn.prepareStatement(checkSql);
                checkPs.setString(1, newUsername);
                ResultSet rs = checkPs.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    conn.rollback();
                    return false;
                }
                // Update username in users table
                PreparedStatement updatePs = conn.prepareStatement(updateSql);
                updatePs.setString(1, newUsername);
                updatePs.setString(2, oldUsername);
                updatePs.executeUpdate();
                // Update username in messages table
                PreparedStatement msgPs = conn.prepareStatement(updateMessages);
                msgPs.setString(1, newUsername);
                msgPs.setString(2, oldUsername);
                msgPs.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "changeUsername failed for " + oldUsername, e);
            return false;
        }
    }

    /**
     * Delete all messages in a room.
     * @return number of messages deleted
     */
    public static int deleteRoomMessages(String room) {
        String sql = "DELETE FROM messages WHERE room = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "deleteRoomMessages failed for " + room, e);
            return 0;
        }
    }

    /**
     * Delete a specific message by ID (only sender can delete).
     * @return true if message was deleted
     */
    public static boolean deleteMessage(long messageId, String sender) {
        String sql = "DELETE FROM messages WHERE id = ? AND sender = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setString(2, sender);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "deleteMessage failed", e);
            return false;
        }
    }

    /**
     * Delete all messages sent by a user.
     * @return number of messages deleted
     */
    public static int deleteUserMessages(String username) {
        String sql = "DELETE FROM messages WHERE sender = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "deleteUserMessages failed for " + username, e);
            return 0;
        }
    }

    // ── Reactions ────────────────────────────────────────────

    /**
     * Toggle a reaction on a message. Returns the new reaction state.
     * @return "added" or "removed"
     */
    public static String toggleReaction(long messageId, String username, String emoji) {
        // Check if reaction exists
        String checkSql = "SELECT id FROM reactions WHERE message_id = ? AND username = ? AND emoji = ?";
        String insertSql = "INSERT INTO reactions (message_id, username, emoji) VALUES (?, ?, ?)";
        String deleteSql = "DELETE FROM reactions WHERE message_id = ? AND username = ? AND emoji = ?";
        try (Connection conn = connect()) {
            // Check existing
            PreparedStatement checkPs = conn.prepareStatement(checkSql);
            checkPs.setLong(1, messageId);
            checkPs.setString(2, username);
            checkPs.setString(3, emoji);
            ResultSet rs = checkPs.executeQuery();
            if (rs.next()) {
                // Remove existing reaction
                PreparedStatement deletePs = conn.prepareStatement(deleteSql);
                deletePs.setLong(1, messageId);
                deletePs.setString(2, username);
                deletePs.setString(3, emoji);
                deletePs.executeUpdate();
                return "removed";
            } else {
                // Add new reaction
                PreparedStatement insertPs = conn.prepareStatement(insertSql);
                insertPs.setLong(1, messageId);
                insertPs.setString(2, username);
                insertPs.setString(3, emoji);
                insertPs.executeUpdate();
                return "added";
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "toggleReaction failed", e);
            return "error";
        }
    }

    /**
     * Get all reactions for a message as a formatted string: "emoji1:user1,user2|emoji2:user3"
     */
    public static String getReactions(long messageId) {
        String sql = "SELECT emoji, username FROM reactions WHERE message_id = ? ORDER BY created_at";
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, java.util.List<String>> reactionMap = new java.util.LinkedHashMap<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String emoji = rs.getString("emoji");
                String user = rs.getString("username");
                reactionMap.computeIfAbsent(emoji, k -> new java.util.ArrayList<>()).add(user);
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "getReactions failed", e);
        }
        boolean first = true;
        for (var entry : reactionMap.entrySet()) {
            if (!first) sb.append("|");
            sb.append(entry.getKey()).append(":").append(String.join(",", entry.getValue()));
            first = false;
        }
        return sb.toString();
    }

    /**
     * Get all reactions for messages in a room (bulk load).
     * Returns map of messageId -> reactions string.
     */
    public static java.util.Map<Long, String> getReactionsForRoom(String room) {
        java.util.Map<Long, String> result = new java.util.HashMap<>();
        String sql = "SELECT r.message_id, r.emoji, r.username FROM reactions r "
                   + "JOIN messages m ON r.message_id = m.id WHERE m.room = ? ORDER BY r.created_at";
        java.util.Map<Long, java.util.Map<String, java.util.List<String>>> allReactions = new java.util.LinkedHashMap<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                long msgId = rs.getLong("message_id");
                String emoji = rs.getString("emoji");
                String user = rs.getString("username");
                allReactions.computeIfAbsent(msgId, k -> new java.util.LinkedHashMap<>())
                    .computeIfAbsent(emoji, k -> new java.util.ArrayList<>()).add(user);
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "getReactionsForRoom failed", e);
        }
        for (var entry : allReactions.entrySet()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (var reactEntry : entry.getValue().entrySet()) {
                if (!first) sb.append("|");
                sb.append(reactEntry.getKey()).append(":").append(String.join(",", reactEntry.getValue()));
                first = false;
            }
            result.put(entry.getKey(), sb.toString());
        }
        return result;
    }
}

package ChatApplication;

import java.sql.*;

public class DatabaseManager {
    // This creates a file named chatapp.db in your project folder
    private static final String DB_URL = "jdbc:sqlite:chatapp.db";

    public static Connection connect() {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(DB_URL);
        } catch (SQLException e) {
            System.out.println("Connection Error: " + e.getMessage());
        }
        return conn;
    }

    // Automatically creates tables if they don't exist
    public static void initializeDatabase() {
        String usersTable = "CREATE TABLE IF NOT EXISTS users ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "username TEXT UNIQUE NOT NULL);";

        String messagesTable = "CREATE TABLE IF NOT EXISTS messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "sender TEXT NOT NULL,"
                + "target TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP);";

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(usersTable);
            stmt.execute(messagesTable);
            System.out.println("Database tables initialized successfully.");
        } catch (SQLException e) {
            System.out.println("Init Error: " + e.getMessage());
        }
    }

    // Saves the user when they connect
    public static void saveUser(String username) {
        String sql = "INSERT OR IGNORE INTO users (username) VALUES (?)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Save User Error: " + e.getMessage());
        }
    }

    // Saves every message sent
    public static void saveMessage(String sender, String target, String content) {
        String sql = "INSERT INTO messages (sender, target, content) VALUES (?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sender);
            pstmt.setString(2, target);
            pstmt.setString(3, content);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Save Message Error: " + e.getMessage());
        }
    }
}
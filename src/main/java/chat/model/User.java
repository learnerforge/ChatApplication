package chat.model;

/**
 * User model for authentication and profile management.
 */
public class User {

    private final long id;
    private final String username;
    private final String passwordHash;
    private final String salt;
    private final String rememberToken;
    private final String createdAt;

    public User(long id, String username, String passwordHash, String salt, String rememberToken, String createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.rememberToken = rememberToken;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getSalt() { return salt; }
    public String getRememberToken() { return rememberToken; }
    public String getCreatedAt() { return createdAt; }
}

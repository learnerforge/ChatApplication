package chat.model;

/**
 * Lightweight representation of a chat message, used for loading
 * history from the database and passing it to connected clients.
 */
public class Message {

    private final long    id;
    private final String  sender;
    private final String  target;
    private final String  content;
    private final String  timestamp;
    private final boolean system;

    public Message(long id, String sender, String target, String content, String timestamp, boolean system) {
        this.id        = id;
        this.sender    = sender;
        this.target    = target;
        this.content   = content;
        this.timestamp = timestamp;
        this.system    = system;
    }

    public long    getId()        { return id; }
    public String  getSender()    { return sender; }
    public String  getTarget()    { return target; }
    public String  getContent()   { return content; }
    public String  getTimestamp() { return timestamp; }
    public boolean isSystem()     { return system; }
    public boolean isPrivate()    { return !target.isEmpty(); }
}

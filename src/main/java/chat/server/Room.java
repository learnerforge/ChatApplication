package chat.server;

import chat.network.WebSocketUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Vector;
import java.util.logging.Logger;

/**
 * A chat room that holds a list of connected clients and provides
 * room-scoped broadcast and user-list operations.
 */
public class Room {

    private static final Logger LOG = Logger.getLogger(Room.class.getName());

    private final String name;
    private final String description;
    private final Vector<ClientHandler> clients = new Vector<>();

    public Room(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName()        { return name; }
    public String getDescription() { return description; }
    public int    getSize()        { synchronized (clients) { return clients.size(); } }

    /** Add a client to this room. */
    void addClient(ClientHandler handler) {
        synchronized (clients) {
            clients.add(handler);
        }
    }

    /** Remove a client from this room. */
    void removeClient(ClientHandler handler) {
        synchronized (clients) {
            clients.remove(handler);
        }
    }

    /** Broadcast a text message to every client in this room. */
    void broadcast(String message) {
        synchronized (clients) {
            Iterator<ClientHandler> it = clients.iterator();
            while (it.hasNext()) {
                ClientHandler client = it.next();
                try {
                    WebSocketUtil.sendText(client.out, message);
                } catch (Exception e) {
                    it.remove();
                }
            }
        }
    }

    /** Broadcast a text message to every client in this room except the sender. */
    void broadcastExcept(String message, ClientHandler sender) {
        synchronized (clients) {
            Iterator<ClientHandler> it = clients.iterator();
            while (it.hasNext()) {
                ClientHandler client = it.next();
                if (client == sender) continue;
                try {
                    WebSocketUtil.sendText(client.out, message);
                } catch (Exception e) {
                    it.remove();
                }
            }
        }
    }

    /** Send a text message to a single client in this room. */
    void sendTo(ClientHandler target, String message) throws IOException {
        WebSocketUtil.sendText(target.out, message);
    }

    /** Send the current user list (USERS:...) to every client in this room. */
    void updateUserList() {
        StringBuilder users = new StringBuilder("USERS:");
        synchronized (clients) {
            for (ClientHandler client : clients) {
                users.append(client.username).append(",");
            }
        }
        broadcast(users.toString());
    }

    /** Get a comma-separated list of usernames in this room. */
    String getUserCsv() {
        StringBuilder sb = new StringBuilder();
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (sb.length() > 0) sb.append(",");
                sb.append(client.username);
            }
        }
        return sb.toString();
    }

    /** Check if a username is taken in this room. */
    boolean hasUser(String username) {
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (c.username.equalsIgnoreCase(username)) return true;
            }
        }
        return false;
    }

    /** Find a client by username (case-insensitive). */
    ClientHandler findUser(String username) {
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (c.username.equalsIgnoreCase(username)) return c;
            }
        }
        return null;
    }
}

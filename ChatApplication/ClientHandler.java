import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ClientHandler implements Runnable {

    Socket socket;
    String username;
    DataInputStream dis;
    DataOutputStream dos;

    public ClientHandler(Socket socket, String username, DataInputStream dis, DataOutputStream dos) {
        this.socket = socket;
        this.username = username;
        this.dis = dis;
        this.dos = dos;
    }

    @Override
    public void run() {
        String message;
        try {
            while (true) {
                message = dis.readUTF();

                // PRIVATE MESSAGE: @username message
                if (message.startsWith("@")) {
                    handlePrivateMessage(message);
                } else {
                    // PUBLIC MESSAGE — format and broadcast
                    String timestamp = new SimpleDateFormat("hh:mm a").format(new Date());
                    String formatted = "[" + timestamp + "] " + username + ": " + message;
                    ChatServer.broadcastMessage(formatted);
                }
            }

        } catch (Exception e) {
            // Client disconnected — clean up
            ChatServer.clients.remove(this);
            ChatServer.broadcastMessage(username + " left the chat");
            ChatServer.updateUserList();
            try {
                socket.close();
            } catch (Exception ex) {
                // ignore
            }
        }
    }

    private void handlePrivateMessage(String message) {
        try {
            int spaceIndex = message.indexOf(" ");
            if (spaceIndex == -1) {
                dos.writeUTF("Usage: @username your message");
                return;
            }

            String targetUser = message.substring(1, spaceIndex);
            String privateContent = message.substring(spaceIndex + 1);

            if (privateContent.isEmpty()) {
                dos.writeUTF("Cannot send empty message");
                return;
            }

            boolean userFound = false;
            for (ClientHandler client : ChatServer.clients) {
                if (client.username.equalsIgnoreCase(targetUser)) {
                    client.dos.writeUTF("[PRIVATE from " + username + "] " + privateContent);
                    if (client != this) {
                        dos.writeUTF("[PRIVATE to " + targetUser + "] " + privateContent);
                    }
                    userFound = true;
                    break;
                }
            }

            if (!userFound) {
                dos.writeUTF("User '" + targetUser + "' not found");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

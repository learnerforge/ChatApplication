import java.io.*;
import java.net.*;

public class ClientHandler implements Runnable {

    Socket socket;

    String username;

    DataInputStream dis;

    DataOutputStream dos;

    public ClientHandler(Socket socket,
                         String username,
                         DataInputStream dis,
                         DataOutputStream dos) {

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

                // PRIVATE MESSAGE
                if (message.contains("@")) {

                    handlePrivateMessage(message);

                } else {

                    // PUBLIC MESSAGE
                    ChatServer.broadcastMessage(message);
                }
            }

        } catch (Exception e) {

            try {

                ChatServer.clients.remove(this);

                ChatServer.broadcastMessage(
                        username + " left the chat"
                );

                ChatServer.updateUserList();

                socket.close();

            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    // Handle Private Messaging
    private void handlePrivateMessage(String message) {

        try {

            // Find username after @
            int atIndex = message.indexOf("@");

            int spaceIndex = message.indexOf(" ", atIndex);

            if (spaceIndex == -1) {

                dos.writeUTF("Invalid private message format");

                return;
            }

            String targetUser =
                    message.substring(atIndex + 1, spaceIndex);

            String privateMsg =
                    "[PRIVATE] " + message;

            boolean userFound = false;

            // Search connected users
            for (ClientHandler client : ChatServer.clients) {

                if (client.username.equalsIgnoreCase(targetUser)) {

                    client.dos.writeUTF(privateMsg);

                    // Also show sender
                    if (client != this) {

                        dos.writeUTF(privateMsg);
                    }

                    userFound = true;

                    break;
                }
            }

            if (!userFound) {

                dos.writeUTF(
                        "User '" + targetUser + "' not found"
                );
            }

        } catch (Exception e) {

            e.printStackTrace();
        }
    }
}
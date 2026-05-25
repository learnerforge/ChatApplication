import java.io.*;
import java.net.*;
import java.util.*;

public class ChatServer {

    public static final int PORT = 5000;

    static Vector<ClientHandler> clients = new Vector<>();

    public static void main(String[] args) {
        try {
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Server started on port " + PORT + "...");

            while (true) {
                Socket socket = serverSocket.accept();
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

                String username = dis.readUTF().trim();

                // Validate username
                if (username.isEmpty()) {
                    dos.writeUTF("ERROR: Username cannot be empty");
                    socket.close();
                    continue;
                }

                // Check for duplicate username
                boolean duplicate = false;
                for (ClientHandler c : clients) {
                    if (c.username.equalsIgnoreCase(username)) {
                        duplicate = true;
                        break;
                    }
                }

                if (duplicate) {
                    dos.writeUTF("ERROR: Username '" + username + "' already taken");
                    socket.close();
                    continue;
                }

                // Accept the client
                dos.writeUTF("ACCEPTED");

                ClientHandler client = new ClientHandler(socket, username, dis, dos);
                clients.add(client);
                new Thread(client).start();

                broadcastMessage(username + " joined the chat");
                updateUserList();
                System.out.println(username + " connected");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void broadcastMessage(String message) {
        synchronized (clients) {
            Iterator<ClientHandler> it = clients.iterator();
            while (it.hasNext()) {
                ClientHandler client = it.next();
                try {
                    client.dos.writeUTF(message);
                } catch (Exception e) {
                    it.remove();
                }
            }
        }
    }

    public static void updateUserList() {
        StringBuilder users = new StringBuilder("USERS:");
        synchronized (clients) {
            for (ClientHandler client : clients) {
                users.append(client.username).append(",");
            }
        }
        broadcastMessage(users.toString());
    }
}

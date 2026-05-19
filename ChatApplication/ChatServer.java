import java.io.*;
import java.net.*;
import java.util.*;

public class ChatServer {

    static Vector<ClientHandler> clients = new Vector<>();

    public static void broadcastMessage(String message) {

        for (ClientHandler client : clients) {

            try {
                client.dos.writeUTF(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void updateUserList() {

        StringBuilder users = new StringBuilder("USERS:");

        for (ClientHandler client : clients) {

            users.append(client.username).append(",");
        }

        broadcastMessage(users.toString());
    }

    public static void main(String[] args) {

        try {

            ServerSocket serverSocket = new ServerSocket(5000);

            System.out.println("Server Started...");

            while (true) {

                Socket socket = serverSocket.accept();

                DataInputStream dis =
                        new DataInputStream(socket.getInputStream());

                DataOutputStream dos =
                        new DataOutputStream(socket.getOutputStream());

                String username = dis.readUTF();

                ClientHandler client =
                        new ClientHandler(socket, username, dis, dos);

                clients.add(client);

                Thread t = new Thread(client);

                t.start();

                broadcastMessage(username + " joined the chat");

                updateUserList();

                System.out.println(username + " connected");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
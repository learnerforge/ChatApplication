import java.io.*;
import java.net.*;

public class ChatClient {

    Socket socket;
    DataInputStream dis;
    DataOutputStream dos;

    public ChatClient(String serverAddress, int port) {

        try {

            socket = new Socket(serverAddress, port);

            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(String msg) {

        try {
            dos.writeUTF(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String receiveMessage() {

        try {
            return dis.readUTF();
        } catch (Exception e) {
            return "";
        }
    }
}
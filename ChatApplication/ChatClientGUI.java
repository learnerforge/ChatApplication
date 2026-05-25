import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

public class ChatClientGUI extends JFrame implements ActionListener {

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 5000;

    JTextArea chatArea;
    JTextField messageField;
    JButton sendButton;
    DefaultListModel<String> userListModel;
    JList<String> userList;
    ChatClient client;
    String username;

    Color backgroundColor = new Color(24, 24, 24);
    Color panelColor = new Color(40, 40, 40);
    Color textColor = Color.WHITE;
    Color accentColor = new Color(0, 170, 255);

    public ChatClientGUI(String username) {
        this.username = username;
        client = new ChatClient(SERVER_HOST, SERVER_PORT);

        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(null,
                    "Cannot connect to server at " + SERVER_HOST + ":" + SERVER_PORT,
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Send username and check server response
        client.sendMessage(username);
        String response = client.receiveMessage();

        if (response.startsWith("ERROR:")) {
            JOptionPane.showMessageDialog(null,
                    response, "Connection Rejected", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Accepted — set up UI
        setTitle("Real-Time Chat - " + username);
        setSize(700, 600);
        setLayout(new BorderLayout());
        getContentPane().setBackground(backgroundColor);

        // Chat Area
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setBackground(backgroundColor);
        chatArea.setForeground(textColor);
        chatArea.setFont(new Font("Consolas", Font.PLAIN, 16));
        chatArea.setBorder(new EmptyBorder(10, 10, 10, 10));

        JScrollPane chatScroll = new JScrollPane(chatArea);

        // Message Field
        messageField = new JTextField();
        messageField.setFont(new Font("Arial", Font.PLAIN, 16));
        messageField.setBackground(panelColor);
        messageField.setForeground(textColor);
        messageField.setCaretColor(Color.WHITE);
        messageField.addActionListener(this);

        // Send Button
        sendButton = new JButton("Send");
        sendButton.setBackground(accentColor);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(this);

        // Bottom Panel
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBackground(backgroundColor);
        bottomPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        bottomPanel.add(messageField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        // Online Users Panel
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setBackground(panelColor);
        userList.setForeground(Color.WHITE);
        userList.setFont(new Font("Arial", Font.BOLD, 15));

        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setPreferredSize(new Dimension(150, 0));

        JPanel rightPanel = new JPanel(new BorderLayout());
        JLabel onlineLabel = new JLabel(" Online Users");
        onlineLabel.setForeground(Color.WHITE);
        onlineLabel.setBackground(panelColor);
        onlineLabel.setOpaque(true);
        rightPanel.add(onlineLabel, BorderLayout.NORTH);
        rightPanel.add(userScroll, BorderLayout.CENTER);

        add(chatScroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
        add(rightPanel, BorderLayout.EAST);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setVisible(true);

        // Receive Thread
        Thread receiveThread = new Thread(() -> {
            try {
                while (true) {
                    String msg = client.receiveMessage();

                    if (msg.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            chatArea.append("\n--- Disconnected from server ---\n");
                            messageField.setEnabled(false);
                            sendButton.setEnabled(false);
                        });
                        break;
                    }

                    if (msg.startsWith("USERS:")) {
                        SwingUtilities.invokeLater(() -> {
                            userListModel.clear();
                            String users = msg.substring(6);
                            String[] userArray = users.split(",");
                            for (String user : userArray) {
                                if (!user.trim().isEmpty()) {
                                    userListModel.addElement(user);
                                }
                            }
                        });
                    } else {
                        chatArea.append(msg + "\n");
                        chatArea.setCaretPosition(chatArea.getDocument().getLength());
                    }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    chatArea.append("\n--- Connection lost ---\n");
                });
            }
        });

        receiveThread.start();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String msg = messageField.getText().trim();
        if (!msg.isEmpty()) {
            client.sendMessage(msg);
            messageField.setText("");
        }
    }

    public static void main(String[] args) {
        String username = JOptionPane.showInputDialog("Enter Username");
        if (username != null && !username.trim().isEmpty()) {
            new ChatClientGUI(username);
        }
    }
}

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ChatClientGUI extends JFrame
        implements ActionListener {

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

        client = new ChatClient("localhost", 5000);

        try {

            client.dos.writeUTF(username);

        } catch (Exception e) {
            e.printStackTrace();
        }

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

        chatArea.setBorder(new EmptyBorder(10,10,10,10));

        JScrollPane chatScroll = new JScrollPane(chatArea);

        // Message Field
        messageField = new JTextField();

        messageField.setFont(new Font("Arial", Font.PLAIN, 16));

        messageField.setBackground(panelColor);

        messageField.setForeground(textColor);

        messageField.setCaretColor(Color.WHITE);

        // Press Enter to Send
        messageField.addActionListener(this);

        // Send Button
        sendButton = new JButton("Send");

        sendButton.setBackground(accentColor);

        sendButton.setForeground(Color.WHITE);

        sendButton.setFocusPainted(false);

        sendButton.addActionListener(this);

        // Bottom Panel
        JPanel bottomPanel = new JPanel(new BorderLayout(10,0));

        bottomPanel.setBackground(backgroundColor);

        bottomPanel.setBorder(new EmptyBorder(10,10,10,10));

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

            while (true) {

                String msg = client.receiveMessage();

                // Update user list
                if (msg.startsWith("USERS:")) {

                    SwingUtilities.invokeLater(() -> {

                        userListModel.clear();

                        String users =
                                msg.substring(6);

                        String[] userArray =
                                users.split(",");

                        for (String user : userArray) {

                            if (!user.trim().isEmpty()) {

                                userListModel.addElement(user);
                            }
                        }
                    });

                } else {

                    chatArea.append(msg + "\n");

                    chatArea.setCaretPosition(
                            chatArea.getDocument().getLength()
                    );
                }
            }
        });

        receiveThread.start();
    }

    // Timestamp
    private String getTimestamp() {

        SimpleDateFormat sdf =
                new SimpleDateFormat("hh:mm a");

        return "[" + sdf.format(new Date()) + "]";
    }

    @Override
    public void actionPerformed(ActionEvent e) {

        String msg = messageField.getText().trim();

        if (!msg.isEmpty()) {

            String formattedMessage =
                    getTimestamp()
                            + " "
                            + username
                            + ": "
                            + msg;

            client.sendMessage(formattedMessage);

            messageField.setText("");
        }
    }

    public static void main(String[] args) {

        String username =
                JOptionPane.showInputDialog("Enter Username");

        if (username != null
                && !username.trim().isEmpty()) {

            new ChatClientGUI(username);
        }
    }
}
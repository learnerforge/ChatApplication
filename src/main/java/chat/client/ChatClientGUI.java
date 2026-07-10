package chat.client;

import chat.config.Config;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;

/**
 * Swing-based desktop GUI client.
 *
 * <p>Replaced by {@code web/index.html} for the web MVP, but retained
 * for future desktop builds.  Connects over raw TCP sockets using
 * {@link ChatClient}.</p>
 */
public class ChatClientGUI extends JFrame implements ActionListener {

    private JTextArea chatArea;
    private JTextField messageField;
    private JButton sendButton;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private ChatClient client;

    private final Color backgroundColor = new Color(24, 24, 24);
    private final Color panelColor      = new Color(40, 40, 40);
    private final Color textColor       = Color.WHITE;
    private final Color accentColor     = new Color(0, 170, 255);

    public ChatClientGUI(String username, String host, int port) {
        client = new ChatClient(host, port);

        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(null,
                    "Cannot connect to server at " + host + ":" + port,
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        client.sendMessage(username);
        String response = client.receiveMessage();

        if (response.startsWith("ERROR:")) {
            JOptionPane.showMessageDialog(null, response, "Connection Rejected", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // ── Window ──
        setTitle(Config.APP_NAME + " — " + username);
        setSize(700, 600);
        setMinimumSize(new Dimension(500, 400));
        setLayout(new BorderLayout());
        getContentPane().setBackground(backgroundColor);

        // ── Chat area ──
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setBackground(backgroundColor);
        chatArea.setForeground(textColor);
        chatArea.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        chatArea.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(null);

        // ── Input bar ──
        messageField = new JTextField();
        messageField.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        messageField.setBackground(panelColor);
        messageField.setForeground(textColor);
        messageField.setCaretColor(Color.WHITE);
        messageField.addActionListener(this);

        sendButton = new JButton("Send");
        sendButton.setBackground(accentColor);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        sendButton.addActionListener(this);

        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBackground(backgroundColor);
        bottomPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        bottomPanel.add(messageField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        // ── User list ──
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setBackground(panelColor);
        userList.setForeground(Color.WHITE);
        userList.setFont(new Font("Segoe UI", Font.BOLD, 14));

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

        // ── Receiver thread ──
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
                            String[] parts = msg.substring(6).split(",");
                            for (String u : parts) {
                                if (!u.trim().isEmpty()) userListModel.addElement(u.trim());
                            }
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            chatArea.append(msg + "\n");
                            chatArea.setCaretPosition(chatArea.getDocument().getLength());
                        });
                    }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> chatArea.append("\n--- Connection lost ---\n"));
            }
        }, "receiver");
        receiveThread.setDaemon(true);
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

    /** Launch with a username prompt. */
    public static void main(String[] args) {
        String username = JOptionPane.showInputDialog("Enter Username");
        if (username != null && !username.trim().isEmpty()) {
            new ChatClientGUI(username.trim(), "localhost", Config.PORT);
        }
    }
}

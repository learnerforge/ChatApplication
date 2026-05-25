# Real-Time Chat Application (using Sockets)

**ID:** 24261A6607

A multi-threaded, real-time chat application built with Java Sockets and Swing.  
Supports multiple concurrent users, public broadcast, private messaging, and an online user list.

---

## Concepts Demonstrated

| Concept | Implementation |
|---|---|
| **Networking (Sockets)** | `ServerSocket` / `Socket` over TCP on port 5000; `DataInputStream` / `DataOutputStream` for UTF message exchange |
| **Multithreading** | One thread per connected client (`ClientHandler implements Runnable`); a dedicated thread on each client for receiving messages |
| **Event Handling** | `ActionListener` on the message text field (Enter key) and Send button |

---

## Project Structure

```
ChatApplication/
├── ChatServer.java      — Server entry point; accepts connections, broadcasts messages
├── ClientHandler.java   — Per-client thread; reads messages, handles private/public routing
├── ChatClient.java      — Low-level network wrapper (socket + streams)
├── ChatClientGUI.java   — Swing GUI client (dark theme, user list, timestamps)
└── *.class              — Compiled bytecode (regenerate with javac)
```

---

## How to Run

### 1. Start the Server

```bash
cd ChatApplication
javac *.java
java ChatServer
```
The server starts on port **5000** and waits for clients.

### 2. Start Clients (one or more)

Open new terminals and run:

```bash
cd ChatApplication
java ChatClientGUI
```

Enter a username when prompted. Each client connects to `localhost:5000`.

---

## Features

| Feature | Usage |
|---|---|
| **Public chat** | Type a message and press Enter — all connected users see it |
| **Private message** | `@username your message` — only the recipient sees it |
| **Online user list** | Shows all connected users in the right panel |
| **Join/Leave notifications** | Broadcast when a user connects or disconnects |
| **Timestamps** | Server prepends `[hh:mm a]` to every public message |
| **Dark-themed GUI** | Swing UI with dark background, Consolas font, blue accent |
| **Duplicate username rejection** | Server rejects duplicate usernames at connect time |
| **Graceful disconnect** | Removes user from list and notifies others on disconnect |

---

## Architecture

```
┌─────────────┐    TCP Socket (port 5000)    ┌──────────────┐
│ ChatServer  │◄────────────────────────────►│ ChatClient   │
│ (ServerSocket)│                             │ (Socket)     │
│ Vector<ClientHandler>│                     └──────┬───────┘
└──────┬──────┘                                     │
       │  Thread-per-client                          │
       ▼                                             ▼
┌─────────────────┐                     ┌──────────────────┐
│ ClientHandler   │                     │ ChatClientGUI    │
│ - reads messages│                     │ - JFrame UI      │
│ - routes PM/bcast│                    │ - receive thread  │
│ - formats output │                    │ - ActionListener  │
└─────────────────┘                     └──────────────────┘
```

- **Server** maintains a `Vector<ClientHandler>` of connected clients.
- **ClientHandler** (one per connection) reads from the socket in a loop and either broadcasts or routes privately.
- **ChatClientGUI** sends raw messages, displays received messages, and updates the user list.
- Messages are formatted on the **server** with `[timestamp] username: message`.

---

## Future Enhancements (Post-Submission)

- Database persistence (message history, user accounts)
- File sharing
- Multiple chat rooms
- SSL/TLS encryption
- Web/mobile frontend

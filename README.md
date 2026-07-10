# Real-Time Chat - Web MVP

## What changed from the Swing version
- `ChatServer.java` and `ClientHandler.java` - same thread-per-client, `Vector<ClientHandler>`
  design as before, but now speak the WebSocket protocol instead of `DataInputStream`/`DataOutputStream`.
- `WebSocketUtil.java` - new helper: does the HTTP → WebSocket handshake and encodes/decodes
  WebSocket frames, all on top of the same raw `Socket`/`ServerSocket` classes (no external
  WebSocket library, so Networking + Multithreading are still genuinely front and center).
- `web/index.html` - replaces `ChatClientGUI.java`. Same dark theme, message bubbles, online
  users list, private messaging (`@username message`), timestamps, and a connection-status
  indicator, but running in any browser instead of Swing.
- `ChatClient.java`, `ChatClientGUI.java` are no longer used - the browser is the new client.
- `DatabaseManager.java` is untouched (not part of this MVP).

## How to run

1. Compile and start the server:
   ```
   cd ChatApplication
   javac *.java
   java ChatServer
   ```
   You should see: `Chat server started on port 5000 (WebSocket)...`

2. Open `web/index.html` directly in a browser (double-click it, or open it via `File > Open`).
   No web server needed for the HTML - it connects out to the Java server over WebSocket.

3. On the login screen:
   - Server address: `localhost` if the browser and server are on the same machine.
   - For LAN testing (item 11 in your plan), enter the server machine's local IP
     (e.g. `192.168.1.23`) instead - make sure port 5000 is allowed through the firewall.
   - Port: `5000` (matches `ChatServer.PORT`).
   - Enter a username and click **Join Chat**.

4. Open the same `index.html` in another tab/browser/machine to test multi-client chat.

## Known limits of this MVP (intentionally out of scope for now)
- No chat history / persistence (server restart clears all state - same as before).
- No login/password - just a username, same as the original.
- No file sharing, chat rooms, or encryption yet - these are the "Part 4/5/6" stretch items
  from your original plan and can be layered on top of this once the web MVP is solid.

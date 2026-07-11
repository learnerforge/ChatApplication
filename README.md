# Real-Time Chat Application

Multi-client real-time chat using Java Sockets, Multithreading, and WebSocket protocol.

## Build

```
mvn clean package
```

## Run (plain WebSocket, no TLS)

```
java -jar target/chat-application-1.0.0.jar
```

The server starts on port 5000. Open `src/main/resources/web/index.html` in a browser (double-click or `File > Open`). The HTML connects to the server via WebSocket.

## Run with TLS (for cloudflared HTTPS)

1. Generate a self-signed keystore:
   - Windows: `.\setup-ssl.ps1`
   - Linux/Mac: `chmod +x setup-ssl.sh && ./setup-ssl.sh`

2. Set `server.ssl.enabled=true` in `src/main/resources/config.properties`

3. Build and start:
   ```
   mvn clean package
   java -jar target/chat-application-1.0.0.jar
   ```

4. Configure and run cloudflared:
   ```
   cloudflared tunnel --config cloudflared-config.yml run
   ```

The server listens on both port 5000 (plain) and 5443 (TLS). cloudflared connects to localhost:5443 with `noTLSVerify: true`.

## Features
- Multi-room chat with /join, /leave
- Private messaging (@username)
- Message editing, deletion, reactions
- Search within room
- Account management (register, login, change password, change username, delete account)
- Persistent history via SQLite
- Dark/light theme toggle

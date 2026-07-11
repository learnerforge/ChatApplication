#!/bin/bash
# Generate a self-signed JKS keystore for the chat server (TLS)
# Then run: mvn clean package && java -jar target/chat-application-1.0.0.jar

set -e

KEYSTORE="chatapp.jks"
PASSWORD="changeit"
ALIAS="chat"

if [ ! -f "$KEYSTORE" ]; then
  echo "Generating self-signed keystore: $KEYSTORE"
  keytool -genkey -alias "$ALIAS" -keyalg RSA \
    -keystore "$KEYSTORE" -keysize 2048 -validity 365 \
    -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -dname "CN=localhost, OU=Chat, O=ChatApp, L=Unknown, ST=Unknown, C=US" \
    -ext san=dns:localhost,ip:127.0.0.1 2>/dev/null
  echo "Keystore created at $KEYSTORE"
else
  echo "Keystore $KEYSTORE already exists"
fi

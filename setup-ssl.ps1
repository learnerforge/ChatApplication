# Generate a self-signed JKS keystore for the chat server (TLS)
# Run this before starting the server if SSL is enabled
# Then: mvn clean package; java -jar target/chat-application-1.0.0.jar

$keystore = "chatapp.jks"
$password = "changeit"
$alias = "chat"

if (-not (Test-Path $keystore)) {
  Write-Host "Generating self-signed keystore: $keystore"
  & keytool -genkey -alias $alias -keyalg RSA `
    -keystore $keystore -keysize 2048 -validity 365 `
    -storepass $password -keypass $password `
    -dname "CN=localhost, OU=Chat, O=ChatApp, L=Unknown, ST=Unknown, C=US" `
    -ext san=dns:localhost,ip:127.0.0.1
  Write-Host "Keystore created at $keystore"
} else {
  Write-Host "Keystore $keystore already exists"
}

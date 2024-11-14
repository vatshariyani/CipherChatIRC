import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import javax.crypto.Mac;
import java.util.Base64;
import java.awt.Toolkit;

public class IRCClient {
    private String nickname;
    private String server;
    private int port;
    private String channel;
    private boolean connected = false;
    private boolean listedChannels = false; // Flag to indicate if channels are listed

    private KeyPair keyPair;
    private SecretKey secretKey;
    private Map<String, PublicKey> publicKeys = new HashMap<>();

    private KeyAgreement keyAgreement;
    private static final String ALGORITHM = "DH";
    private static final String AES_ALGORITHM = "AES";
    private static final String MAC_ALGORITHM = "HmacSHA256";

    public IRCClient(String nickname, String server, int port) {
        this.nickname = nickname;
        this.server = server;
        this.port = port;
        this.channel = "#chat"; // Default channel
    }

    public void connect() {
        try {
            // Generate Diffie-Hellman key pair for key exchange
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(ALGORITHM);
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();

            // Initialize key agreement
            keyAgreement = KeyAgreement.getInstance(ALGORITHM);
            keyAgreement.init(keyPair.getPrivate());

            Socket socket = new Socket(server, port);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.write("NICK " + nickname + "\r\n");
            writer.write("USER " + nickname + " 0 * :" + nickname + "\r\n");
            writer.flush();

            // Notification handling function
            Runnable handleNotification = () -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("Server: " + line); // Print server replies
                        if (line.contains("001")) {
                            connected = true;
                            System.out.println("Connected to IRC server.");
                        }
                        if (line.startsWith("PING")) {
                            writer.write("PONG" + line.substring(4) + "\r\n");
                            writer.flush();
                        } else if (line.contains("PRIVMSG " + channel)) {
                            String sender = line.split("!")[0].substring(1);
                            String encryptedMessage = line.split("PRIVMSG " + channel + " :")[1];
                            String decryptedMessage = decryptMessage(encryptedMessage); // Call with just the encrypted message
                            System.out.println(sender + ": " + decryptedMessage);
                        }
                        // Handle key exchange
                        else if (line.contains("@keyexchange")) {
                            String encryptedKey = line.split(":")[1].trim();
                            byte[] encryptedKeyBytes = Base64.getDecoder().decode(encryptedKey);
                            SecretKey sharedKey = decryptSessionKey(encryptedKeyBytes);
                            System.out.println("Shared key established.");
                            secretKey = sharedKey;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            };
            new Thread(handleNotification).start();

            new Thread(() -> {
                try {
                    BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
                    String input;
                    while ((input = consoleReader.readLine()) != null) {
                        if (!connected) {
                            System.out.println("Not connected to IRC server. Cannot send message.");
                            continue;
                        }

                        // Command Processing
                        if (input.equalsIgnoreCase("/quit")) {
                            try {
                                writer.write("QUIT\r\n");
                                writer.flush();
                                socket.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            break;
                        } else if (input.startsWith("/join")) {
                            String[] parts = input.split(" ");
                            if (parts.length == 2) {
                                channel = parts[1];
                                try {
                                    writer.write("JOIN " + channel + "\r\n");
                                    writer.flush();
                                    System.out.println("Joined channel: " + channel);
                                    broadcastEncryptedSessionKey();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else {
                                System.out.println("Invalid command. Usage: /join <channel>");
                            }
                        } else if (input.equalsIgnoreCase("/part")) {
                            try {
                                writer.write("PART " + channel + "\r\n");
                                writer.flush();
                                System.out.println("Left channel: " + channel);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (input.equalsIgnoreCase("/list")) {
                            List<String> channels = listChannels();
                            System.out.println("Available channels:");
                            for (String ch : channels) {
                                System.out.println(ch);
                            }
                            listedChannels = true;
                            continue;
                        } else if (input.equalsIgnoreCase("/help")) {
                            displayHelp();
                        } else if (input.equalsIgnoreCase("/listusers")) {
                            listUsers(writer);
                        } else if (input.startsWith("/nick")) {
                            String[] parts = input.split(" ");
                            if (parts.length == 2) {
                                String newNickname = parts[1];
                                try {
                                    writer.write("NICK " + newNickname + "\r\n");
                                    writer.flush();
                                    System.out.println("Changed nickname to: " + newNickname);
                                    nickname = newNickname;
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else {
                                System.out.println("Invalid command. Usage: /nick <new_nickname>");
                            }
                        } else if (input.startsWith("/msg NickServ REGISTER ")) {
                            String[] parts = input.split(" ", 4);
                            if (parts.length == 4) {
                                String password = parts[3];
                                String email = parts[4];
                                String message = "REGISTER " + password + " " + email;
                                writer.write("PRIVMSG NickServ :" + message + "\r\n");
                                writer.flush();
                                System.out.println("Sent registration request to NickServ.");
                            } else {
                                System.out.println("Invalid command. Usage: /msg NickServ REGISTER <password> <email>");
                            }
                        } else {
                            if (!input.startsWith("/")) {
                                try {
                                    // Encrypt the message using the session key
                                    String encryptedMessage = encryptMessage(input);
                                    writer.write("PRIVMSG " + channel + " :" + encryptedMessage + "\r\n");
                                    writer.flush();
                                    System.out.println("you: " + input);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else if (input.startsWith("PRIVMSG " + channel)) {
                                String line = reader.readLine();
                                String sender = line.split("!")[0].substring(1);
                                String encryptedMessage = line.split("PRIVMSG " + channel + " :")[1];
                                String decryptedMessage = decryptMessage(encryptedMessage);
                                System.out.println(sender + ": " + decryptedMessage);
                            } else if (input.equalsIgnoreCase("/topic")) {
                                try {
                                    displayChannelTopic(writer);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else {
                                try {
                                    writer.write(input + "\r\n");
                                    writer.flush();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }

                        try {
                            writer.flush();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            // Broadcast public key to initiate key exchange
            broadcastPublicKey();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Step 1: Broadcast public key for Diffie-Hellman key exchange
    private void broadcastPublicKey() throws Exception {
        byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
        String encodedPublicKey = Base64.getEncoder().encodeToString(publicKeyBytes);
        try {
            // Send public key to IRC server
            System.out.println("Public key shared with server: " + encodedPublicKey);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Step 2: Receive public key, calculate shared secret key and send the shared key
    private SecretKey decryptSessionKey(byte[] encryptedSessionKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        byte[] decryptedKey = cipher.doFinal(encryptedSessionKey);
        return new SecretKeySpec(decryptedKey, 0, decryptedKey.length, AES_ALGORITHM);
    }

    // Step 3: Encrypt message using AES
    private String encryptMessage(String message) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encryptedBytes = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));
        String encryptedMessage = Base64.getEncoder().encodeToString(encryptedBytes);
        String mac = generateMAC(encryptedMessage);
        return encryptedMessage + ":" + mac;
    }

    // Step 4: Decrypt message using AES
    private String decryptMessage(String encryptedMessage) throws Exception {
        String[] parts = encryptedMessage.split(":");
        String message = parts[0];
        String receivedMac = parts[1];

        if (!verifyMAC(message, receivedMac)) {
            throw new SecurityException("Message integrity check failed.");
        }

        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey);  // Use the instance secretKey directly
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(message));
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    // Step 5: Generate HMAC for message integrity
    private String generateMAC(String message) throws Exception {
        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        mac.init(secretKey);
        byte[] macBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(macBytes);
    }

    // Step 6: Verify HMAC for message integrity
    private boolean verifyMAC(String message, String receivedMac) throws Exception {
        String computedMac = generateMAC(message);
        return computedMac.equals(receivedMac);
    }

    // Broadcast encrypted session key
    private void broadcastEncryptedSessionKey() throws Exception {
        byte[] encryptedKey = encryptSessionKey(secretKey);
        String encodedKey = Base64.getEncoder().encodeToString(encryptedKey);
        // Send the encrypted session key to all users
        System.out.println("Encrypted session key sent: " + encodedKey);
    }

    private byte[] encryptSessionKey(SecretKey sessionKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKeys.get(nickname)); // Public key of the recipient
        return cipher.doFinal(sessionKey.getEncoded());
    }

    // List available channels (stub for now)
    private List<String> listChannels() {
        List<String> channels = new ArrayList<>();
        channels.add("#general");
        channels.add("#help");
        return channels;
    }

    // Display available commands
    private void displayHelp() {
        System.out.println("Available commands:");
        System.out.println("/quit - Quit the IRC server");
        System.out.println("/join <channel> - Join a channel");
        System.out.println("/part - Leave the current channel");
        System.out.println("/list - List available channels");
        System.out.println("/listusers - List users in the current channel");
        System.out.println("/nick <new_nickname> - Change your nickname");
        System.out.println("/msg NickServ REGISTER <password> <email> - Register with NickServ");
        System.out.println("/topic - Display the channel topic");
    }

    // List users in the current channel
    private void listUsers(BufferedWriter writer) {
        try {
            writer.write("NAMES " + channel + "\r\n");
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Display the channel topic
    private void displayChannelTopic(BufferedWriter writer) {
        try {
            writer.write("TOPIC " + channel + "\r\n");
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
        try {
            System.out.print("Enter your nickname: ");
            String nickname = consoleReader.readLine();
            System.out.print("Enter IRC server address: ");
            String server = consoleReader.readLine();
            System.out.print("Enter IRC server port: ");
            int port = Integer.parseInt(consoleReader.readLine());
            IRCClient client = new IRCClient(nickname, server, port);
            client.connect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

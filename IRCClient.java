import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
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

    public IRCClient(String nickname, String server, int port) {
        this.nickname = nickname;
        this.server = server;
        this.port = port;
        this.channel = "#chat"; // Default channel
    }

    public void connect() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
            secretKey = generateRandomSecretKey();
            System.out.println("Secret Key: " + secretKey);
            System.out.println("Session Key Generated: " + Base64.getEncoder().encodeToString(secretKey.getEncoded()));

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
                            String message = line.split("PRIVMSG " + channel + " :")[1];
                            System.out.println(sender + ": " + message);
                        } // Check if the message is a notification
                        else if (line.contains("JOIN " + channel) && !line.contains(nickname)) {
                            String user = line.split("!")[0].substring(1);
                            System.out.println(user + " joined " + channel);
                            handleUserJoin(user, writer);
                        }
                        if (line.startsWith("@notification")) {
                            handleNotification(line.substring("@notification".length()).trim());
                        } else if (line.contains("VERSION")) {
                            String sender = line.split("!")[0].substring(1);
                            String reply = "NOTICE " + sender + " :☺VERSION IRCClient v1.0☺\r\n";
                            try {
                                writer.write(reply);
                                writer.flush();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
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
                                    broadcastEncryptedSessionKey(encryptSessionKeys(secretKey, publicKeys), writer);
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
                            List<String> channels = null;
                            try {
                                channels = listChannels(socket, writer);
                                System.out.println("Available channels:");
                                for (String channel : channels) {
                                    System.out.println(channel);
                                }
                                listedChannels = true; // Set the flag
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            continue; // Skip the rest of the loop and wait for next input
                        } else if (input.equalsIgnoreCase("/help")) {
                            try {
                                displayHelp();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (input.startsWith("/msg ")) {
                            try {
                                String[] parts = input.split(" ", 3);
                                if (parts.length == 3) {
                                    String recipient = parts[1];
                                    String message = parts[2];
                                    writer.write("PRIVMSG " + recipient + " :" + message + "\r\n");
                                    writer.flush();
                                    System.out.println("you: " + message); // Display message sent by user
                                } else {
                                    System.out.println("Invalid command. Usage: /msg <nickname> <message>");
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (input.equalsIgnoreCase("/listusers")) {
                            try {
                                listUsers(writer);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (input.startsWith("/nick")) {
                            String[] parts = input.split(" ");
                            if (parts.length == 2) {
                                String newNickname = parts[1];
                                try {
                                    writer.write("NICK " + newNickname + "\r\n");
                                    writer.flush();
                                    System.out.println("Changed nickname to: " + newNickname);
                                    nickname = newNickname; // Update the nickname
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else {
                                System.out.println("Invalid command. Usage: /nick <new_nickname>");
                            }
                        } else if (input.startsWith("/msg NickServ REGISTER ")) {
                            try {
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
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else {
                            if (!input.startsWith("/")) {
                                // Assuming input is a message to be sent to the current channel
                                try {
                                    // Encrypt the message using the session key
                                    String encryptedMessage = encryptMessage(input, secretKey);
                                    System.out.println("Original Message: " + input);
                                    System.out.println("Encrypted Message: " + encryptedMessage);
                                    writer.write("PRIVMSG " + channel + " :" + encryptedMessage + "\r\n");
                                    writer.flush();
                                    System.out.println("you: " + input); // Display message sent by user
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else if (input.startsWith("PRIVMSG " + channel)){
                                // Decrypt incoming message
                                String line = reader.readLine();
                                String sender = line.split("!")[0].substring(1);
                                String encryptedMessage = line.split("PRIVMSG " + channel + " :")[1];
                                String decryptedMessage = decryptMessage(encryptedMessage, secretKey);
                                System.out.println(sender + ": " + decryptedMessage);
                            
                                // Update the main loop to handle the command for displaying channel topics
                            } else if (input.equalsIgnoreCase("/topic")) {
                                try {
                                    displayChannelTopic(writer);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else {
                                // Handle other commands
                                try {
                                    writer.write(input + "\r\n"); // Send the command directly
                                    writer.flush();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        // Flush the writer after each input
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

            // Step 3: Broadcast Encrypted Session Key
            broadcastEncryptedSessionKey(encryptSessionKeys(secretKey, publicKeys), writer);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleUserJoin(String user, BufferedWriter writer) {
        try {
            // Broadcast the encrypted session key to the new user
            String encodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());
            writer.write("PRIVMSG " + user + " :" + "@keyexchange " + encodedKey + "\r\n");
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Step 2: Encrypt Session Key for Each User
    private Map<String, byte[]> encryptSessionKeys(SecretKey sessionKey, Map<String, PublicKey> publicKeys) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Map<String, byte[]> encryptedSessionKeys = new HashMap<>();
        Cipher cipher = Cipher.getInstance("RSA");
        for (Map.Entry<String, PublicKey> entry : publicKeys.entrySet()) {
            cipher.init(Cipher.ENCRYPT_MODE, entry.getValue());
            byte[] encryptedKey = cipher.doFinal(sessionKey.getEncoded());
            encryptedSessionKeys.put(entry.getKey(), encryptedKey);
        }
        return encryptedSessionKeys;
    }

    // Step 3: Broadcast Encrypted Session Key
    private void broadcastEncryptedSessionKey(Map<String, byte[]> encryptedSessionKeys, BufferedWriter writer) {
        try {
            for (Map.Entry<String, byte[]> entry : encryptedSessionKeys.entrySet()) {
                String recipient = entry.getKey();
                byte[] encryptedKey = entry.getValue();
                String encodedKey = Base64.getEncoder().encodeToString(encryptedKey);
                writer.write("PRIVMSG " + recipient + " :" + "@keyexchange " + encodedKey + "\r\n");
                writer.flush();

                // Notify about the key exchange
                System.out.println("Key exchanged with " + recipient);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Step 4: Decrypt Session Key
    private SecretKey decryptSessionKey(byte[] encryptedSessionKey) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        byte[] decryptedKey = cipher.doFinal(encryptedSessionKey);
        return new SecretKeySpec(decryptedKey, 0, decryptedKey.length, "AES");
    }

    // Step 5: Secure Communication
    private String encryptMessage(String message, SecretKey sessionKey) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey);
        byte[] encryptedBytes = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    private String decryptMessage(String encryptedMessage, SecretKey sessionKey) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, sessionKey);
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedMessage));
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    private SecretKey generateRandomSecretKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(128);
        return keyGen.generateKey();
    }

    private List<String> listChannels(Socket socket, BufferedWriter writer) throws Exception {
        writer.write("LIST\r\n");
        writer.flush();

        List<String> channels = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println("Server: " + line); // Print server replies
            if (line.startsWith(":")) {
                continue;
            }
            if (line.contains(" ")) {
                channels.add(line.split(" ")[1]);
            }
        }
        return channels;
    }

    // Method to request and display the channel topic
    private void displayChannelTopic(BufferedWriter writer) throws Exception {
        writer.write("TOPIC " + channel + "\r\n");
        writer.flush();
    }

    // Method to handle notifications
    private void handleNotification(String notification) {
        System.out.println("Notification: " + notification);
        // Play a notification sound
        Toolkit.getDefaultToolkit().beep();
    }

    private void listUsers(BufferedWriter writer) throws Exception {
        writer.write("NAMES " + channel + "\r\n");
        writer.flush();
    }

    private void displayHelp() {
        System.out.println("Available Commands:");
        System.out.println("/join <channel> - Join a channel");
        System.out.println("/part - Leave the current channel");
        System.out.println("/quit - Disconnect from the server");
        System.out.println("/list - List available channels");
        System.out.println("/listusers - List users in the channel");
        System.out.println("/msg <nickname> <message> - Send a private message to a user");
        System.out.println("/nick <new_nickname> - Change your nickname");
        System.out.println("/topic - Display channel topics");
        System.out.println("/help - Display this help message");
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

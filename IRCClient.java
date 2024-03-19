import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

public class IRCClient {
    private String nickname;
    private String server;
    private int port;
    private String channel;
    private boolean connected = false;
    private boolean listedChannels = false; // Flag to indicate if channels are listed

    public IRCClient(String nickname, String server, int port) {
        this.nickname = nickname;
        this.server = server;
        this.port = port;
        this.channel = "#chat"; // Default channel
    }

    public void connect() {
        try {
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
                        } else if (input.equalsIgnoreCase("/msg")) {
                            try {
                                System.out.print("Enter recipient's nickname: ");
                                String recipient = consoleReader.readLine();
                                System.out.print("Enter message: ");
                                String message = consoleReader.readLine();
                                writer.write("PRIVMSG " + recipient + " :" + message + "\r\n");
                                writer.flush();
                                System.out.println("you: " + message); // Display message sent by user
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (input.equalsIgnoreCase("/listusers")) {
                            try {
                                listUsers(writer);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else {
                            if (!input.startsWith("/")) {
                                // Assuming input is a message to be sent to the current channel
                                try {
                                    writer.write("PRIVMSG " + channel + " :" + input + "\r\n");
                                    writer.flush();
                                    System.out.println("you: " + input); // Display message sent by user
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
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

        } catch (Exception e) {
            e.printStackTrace();
        }
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
        System.out.println("/msg - Send a private message to a user");
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

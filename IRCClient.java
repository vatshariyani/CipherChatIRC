import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
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

            new Thread(() -> {
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
                            handlePrivateMessage(line);
                        } else if (line.contains("VERSION")) {
                            handleVersionRequest(line, writer);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            new Thread(() -> {
                try {
                    BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
                    String input;
                    while ((input = consoleReader.readLine()) != null) {
                        handleUserInput(input, writer, socket, consoleReader);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleUserInput(String input, BufferedWriter writer, Socket socket, BufferedReader consoleReader) {
        try {
            if (!connected) {
                System.out.println("Not connected to IRC server. Cannot send message.");
                return;
            }
            if (input.equalsIgnoreCase("/quit")) {
                writer.write("QUIT\r\n");
                writer.flush();
                socket.close();
                return;
            }
            if (input.startsWith("/join")) {
                handleJoinCommand(input, writer);
                return;
            }
            if (input.equalsIgnoreCase("/part")) {
                handlePartCommand(writer);
                return;
            }
            if (input.equalsIgnoreCase("/list")) {
                handleListCommand(writer, socket);
                return;
            }
            if (input.equalsIgnoreCase("/listusers")) {
                handleListUsersCommand(writer);
                return;
            }
            if (input.equalsIgnoreCase("/msg")) {
                handlePrivateMessageInput(writer, consoleReader);
                return;
            }
            if (!input.startsWith("/")) {
                handleRegularMessage(input, writer);
                return;
            }
            handleOtherCommands(input, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleJoinCommand(String input, BufferedWriter writer) throws Exception {
        String[] parts = input.split(" ");
        if (parts.length == 2) {
            channel = parts[1];
            writer.write("JOIN " + channel + "\r\n");
            writer.flush();
            System.out.println("Joined channel: " + channel);
        } else {
            System.out.println("Invalid command. Usage: /join <channel>");
        }
    }

    private void handlePartCommand(BufferedWriter writer) throws Exception {
        writer.write("PART " + channel + "\r\n");
        writer.flush();
        System.out.println("Left channel: " + channel);
    }

    private void handleListCommand(BufferedWriter writer, Socket socket) throws Exception {
        List<String> channels = listChannels(socket, writer);
        System.out.println("Available channels:");
        for (String channel : channels) {
            System.out.println(channel);
        }
        listedChannels = true; // Set the flag
    }

    private void handleListUsersCommand(BufferedWriter writer) throws Exception {
        writer.write("NAMES " + channel + "\r\n");
        writer.flush();
    }

    private void handlePrivateMessageInput(BufferedWriter writer, BufferedReader consoleReader) throws Exception {
        System.out.print("Enter recipient's nickname: ");
        String recipient = consoleReader.readLine();
        System.out.print("Enter message: ");
        String message = consoleReader.readLine();
        writer.write("PRIVMSG " + recipient + " :" + message + "\r\n");
        writer.flush();
        System.out.println("you: " + message); // Display message sent by user
    }

    private void handleRegularMessage(String input, BufferedWriter writer) throws Exception {
        writer.write("PRIVMSG " + channel + " :" + input + "\r\n");
        writer.flush();
        System.out.println("you: " + input); // Display message sent by user
    }

    private void handleOtherCommands(String input, BufferedWriter writer) throws Exception {
        writer.write(input + "\r\n"); // Send the command directly
        writer.flush();
    }

    private void handlePrivateMessage(String line) {
        String sender = line.split("!")[0].substring(1);
        String message = line.split("PRIVMSG " + channel + " :")[1];
        System.out.println(sender + ": " + message);
    }

    private void handleVersionRequest(String line, BufferedWriter writer) throws Exception {
        String sender = line.split("!")[0].substring(1);
        String reply = "NOTICE " + sender + " :☺VERSION IRCClient v1.0☺\r\n";
        writer.write(reply);
        writer.flush();
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

    private void displayHelp() {
        System.out.println("Available Commands:");
        System.out.println("/join <channel> - Join a channel");
        System.out.println("/part - Leave the current channel");
        System.out.println("/quit - Disconnect from the server");
        System.out.println("/list - List available channels");
        System.out.println("/listusers - List users in the channel");
        System.out.println("/msg - Send a private message to a user");
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


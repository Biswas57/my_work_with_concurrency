import java.io.*;
import java.net.*;
import java.util.*;

public class Client {
    private static DatagramPacket packet;
    private static DatagramSocket socket;
    private static int serverPort;
    private static InetAddress hostAddress;
    private static Scanner scanner = new Scanner(System.in);

    // define constants for easier correlation
    public static final int MAX_SIZE = 1024,
            MAX_FILE_SIZE = 102400,
            TIMEOUT_MS = 600,
            MAX_RETRIES = 16,

            FIRST_CONN = 0,
            LOGIN = 1,
            CRT = 2,
            MSG = 3,
            DLT = 4,
            EDT = 5,
            LST = 6,
            RDT = 7,
            UPD = 8,
            DWN = 9,
            RMV = 10,
            XIT = 11,

            FAILURE = 0,
            SUCCESS = 1,
            // stands for from client
            FC = 2,
            UNAUTHENTICATED = 3;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java Client <server_port>");
            return;
        }

        serverPort = Integer.parseInt(args[0]);
        hostAddress = InetAddress.getByName("127.0.0.1");

        socket = new DatagramSocket();
        socket.setSoTimeout(TIMEOUT_MS);

        String directory = new File("").getAbsolutePath();
        String username = null;
        boolean authorized = false;

        while (!authorized) {
            while (true) {
                System.out.print("Enter username: ");
                try {
                    username = scanner.nextLine();
                } catch (Exception e) {
                    System.err.println("Enter an actual username!!!");
                    continue;
                }
                sendAndReceive(Event.createEvent(FIRST_CONN, FC, username, "Log in request"));
                String reply = Event.getPacketData(packet);

                if (Event.getStatus(reply) == SUCCESS) { // instead of SUCCESS
                    System.out.print(Event.getContent(reply));
                    break;
                } else {
                    System.out.println(Event.getContent(reply));
                }
            }

            String password;
            try {
                password = scanner.nextLine();
            } catch (Exception e) {
                System.err.println("Please enter an actual password!");
                continue;
            }
            String reply = sendAndReceive(Event.createEvent(LOGIN, FC, username, password));

            if (Event.getStatus(reply) == SUCCESS) {
                System.out.println("Welcome to WebForum!!");
                authorized = true;
            } else {
                System.out.println(Event.getContent(reply));
            }
        }

        while (authorized) {
            System.out
                    .println("Enter one of the following commands: CRT, MSG, DLT, EDT, LST, RDT, UPD, DWN, RMV, XIT: ");

            String[] command_str;
            try {
                command_str = scanner.nextLine().split(" ");
            } catch (Exception e) {
                System.out.println("Please enter an actual command!!");
                continue;
            }

            int command = commandInteger(command_str[0]);
            String[] content = command_str.length > 1
                    ? Arrays.copyOfRange(command_str, 1, command_str.length)
                    : null;
            String content_str = content != null ? String.join(" ", content) : null;
            String reply;

            switch (command) {
                case CRT:
                    if (content == null) {
                        System.out.println("Usage: CRT <threadtitle>");
                        break;
                    }
                    reply = execCommand(command, content_str, username);
                    System.out.println(Event.getContent(reply));
                    break;

                case MSG:
                    if (content == null || content.length < 2) {
                        System.out.println("Usage: MSG <threadtitle> <message>");
                        break;
                    }
                    reply = execCommand(command, content_str, username);
                    System.out.println(Event.getContent(reply));
                    break;

                case DLT:
                    if (content == null || content.length < 2) {
                        System.out.println("Usage: DLT <threadtitle> <messagenumber>");
                        break;
                    }
                    reply = execCommand(command, content_str, username);
                    System.out.println(Event.getContent(reply));
                    break;

                case EDT:
                    if (content == null || content.length < 3) {
                        System.out.println("Usage: EDT <threadtitle> <messagenumber> <message>");
                        break;
                    }
                    try {
                        Integer.parseInt(content[1]);
                    } catch (NumberFormatException nfe) {
                        System.out.println("Message number must be an integer");
                        break;
                    }
                    reply = execCommand(command, content_str, username);
                    System.out.println(Event.getContent(reply));
                    break;

                case LST:
                    if (content != null) {
                        System.out.println("Usage: LST");
                        break;
                    }
                    reply = execCommand(command, content_str, username);
                    if (Event.getStatus(reply) == FAILURE) {
                        System.out.println(Event.getContent(reply));
                    } else {
                        String[] threadNames = Event.getContent(reply).split(" ");
                        System.out.println("Currently active threads:");
                        for (String threadTitle : threadNames) {
                            System.out.println(threadTitle);
                        }
                    }
                    break;

                case RDT:
                    if (content == null) {
                        System.out.println("Usage: RDT <threadtitle>");
                        break;
                    }
                    String threadTitle = content[0];
                    reply = execCommand(command, content_str, username);
                    if (Event.getStatus(reply) == FAILURE) {
                        System.out.println(Event.getContent(reply));
                    } else {
                        String[] threadLines = Event.getContent(reply).split(";");
                        for (String line : threadLines) {
                            System.out.println(line);
                        }
                    }
                    break;

                case UPD:
                    if (content == null || content.length < 2) {
                        System.out.println("Usage: UPD <threadtitle> <filename>");
                        break;
                    }
                    File[] uploadFileList = new File(directory).listFiles();
                    threadTitle = content[0];
                    String uploadFileName = content[1];
                    String uploadFilePath = null;
                    if (uploadFileList != null) {
                        for (File file : uploadFileList) {
                            if (file.getName().equals(uploadFileName)) {
                                uploadFilePath = directory + '/' + uploadFileName;
                                break;
                            }
                        }
                    }
                    if (uploadFilePath == null) {
                        System.out.println("Cannot find file in source directory path.");
                        break;
                    }
                    reply = execCommand(command, content_str, username);
                    if (Event.getStatus(reply) == FAILURE) {
                        System.out.println(Event.getContent(reply));
                        break;
                    }

                    try (Socket uploadTcpSocket = new Socket(hostAddress, serverPort)) {
                        handleFileUpload(uploadTcpSocket, uploadFilePath);
                    }
                    DatagramPacket ack = new DatagramPacket(new byte[MAX_SIZE], MAX_SIZE);
                    socket.setSoTimeout(TIMEOUT_MS);
                    try {
                        socket.receive(ack);
                    } catch (SocketTimeoutException e) {
                        System.out.println("Warning: no confirmation – assuming success.");
                    }
                    System.out.println(uploadFileName + " successfully uploaded to " + threadTitle + " thread");
                    break;

                case DWN:
                    if (content == null || content.length < 2) {
                        System.out.println("Usage: DWN <threadtitle> <filename>");
                        break;
                    }

                    File[] downloadFileList = new File(directory).listFiles();
                    String downloadFileName = content[1];
                    boolean fileExists = false;
                    if (downloadFileList != null) {
                        for (File file : downloadFileList) {
                            if (file.getName().equals(downloadFileName)) {
                                fileExists = true;
                                break;
                            }
                        }
                    }
                    if (fileExists) {
                        System.out.println("File already exists in current directory.");
                        break;
                    }

                    reply = execCommand(command, content_str, username);
                    if (Event.getStatus(reply) == FAILURE) {
                        System.out.println(Event.getContent(reply));
                        break;
                    }

                    String downloadFilePath = directory + '/' + downloadFileName;
                    try (Socket downloadTcpSocket = new Socket(hostAddress, serverPort)) {
                        handleFileDownload(downloadTcpSocket, downloadFilePath);
                    }

                    socket.setSoTimeout(TIMEOUT_MS);
                    DatagramPacket udpAck = new DatagramPacket(new byte[MAX_SIZE], MAX_SIZE);
                    try {
                        socket.receive(udpAck);
                    } catch (SocketTimeoutException e) {
                        System.out.println("Warning: no confirmation from server, assuming success.");
                    }

                    System.out.println(downloadFileName + " successfully downloaded to working directory.");
                    break;

                case RMV:
                    if (content == null) {
                        System.out.println("Usage: RMV <threadtitle>");
                        break;
                    }
                    reply = execCommand(command, content_str, username);
                    System.out.println(Event.getContent(reply));
                    break;

                case XIT:
                    sendAndReceive(Event.createEvent(XIT, FC, username, "exit"));
                    System.out.println("Goodbye");
                    System.exit(0);
                    break;

                default:
                    System.out.println("Invalid command");
                    break;
            }
        }
    }

    private static String sendAndReceive(String outgoing) throws Exception {
        byte[] outBuf = outgoing.getBytes();
        DatagramPacket request = new DatagramPacket(outBuf, outBuf.length, hostAddress, serverPort);
        DatagramPacket response = new DatagramPacket(new byte[MAX_SIZE], MAX_SIZE);

        int actionCode = Event.getAction(outgoing);
        int max = actionCode == DWN ? 1 : MAX_RETRIES;
        for (int attempt = 0; attempt < max; attempt++) {
            socket.send(request);

            try {
                socket.receive(response);
                String reply = Event.getPacketData(response);

                if (Event.getStatus(reply) == UNAUTHENTICATED) {
                    System.err.println("Server says you’re not logged in");
                    break;
                }

                if (Event.getAction(reply) == actionCode) {
                    packet = response;
                    return reply;
                }
            } catch (SocketTimeoutException e) {
                System.err.println("Timed out – retrying " + (attempt + 1) + "/" + MAX_RETRIES);
                continue;
            }
        }

        throw new IOException("No valid response after " + MAX_RETRIES + " attempts");
    }

    private static void handleFileUpload(Socket tcpSocket, String filePath) throws Exception {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(filePath));
                BufferedOutputStream bos = new BufferedOutputStream(tcpSocket.getOutputStream())) {

            byte[] buf = new byte[MAX_FILE_SIZE];
            int n;
            while ((n = bis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            bos.flush();
            tcpSocket.shutdownOutput();

            tcpSocket.getInputStream().readNBytes(1024);
        }
    }

    private static void handleFileDownload(Socket tcpSocket, String downloadPath) throws Exception {
        InputStream inputStream = tcpSocket.getInputStream();
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        FileOutputStream fos = new FileOutputStream(downloadPath);
        BufferedOutputStream bos = new BufferedOutputStream(fos);

        byte[] DWNBytes = new byte[MAX_FILE_SIZE];
        int DWNLength;
        while ((DWNLength = bis.read(DWNBytes)) != -1)
            bos.write(DWNBytes, 0, DWNLength);
        bos.flush();

        OutputStream out = tcpSocket.getOutputStream();
        String feedbackMessage = "Success";
        out.write(feedbackMessage.getBytes());
        fos.close();

        bos.close();
        bis.close();
    }

    private static String execCommand(int action, String content, String username) {
        try {
            if (content == null)
                content = "";
            String evt = Event.createEvent(action, FC, username, content);
            return sendAndReceive(evt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int commandInteger(String command) {
        if (command.length() > 3) {
            return 0;
        }

        switch (command) {
            case "CRT":
                return 2;
            case "MSG":
                return 3;
            case "DLT":
                return 4;
            case "EDT":
                return 5;
            case "LST":
                return 6;
            case "RDT":
                return 7;
            case "UPD":
                return 8;
            case "DWN":
                return 9;
            case "RMV":
                return 10;
            case "XIT":
                return 11;
            default:
                return 0;
        }
    }
}

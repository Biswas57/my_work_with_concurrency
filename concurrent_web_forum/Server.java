import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    private static int serverPort;
    private static ServerSocket tcpListener;
    private static DatagramSocket socket;
    private static MessageHandler handler;
    // from spec: “We will spawn a maximum of three concurrent clients
    // when testing with multiple concurrent clients.”
    private static final ExecutorService pool = Executors.newFixedThreadPool(10);

    // define constants for easier correlation
    public static final int MAX_SIZE = 1024,
            MAX_FILE_SIZE = 102400,
            TIMEOUT_MS = 1000,
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
            System.err.println("Usage: java Server <server_port>");
            return;
        }

        serverPort = Integer.parseInt(args[0]);
        socket = new DatagramSocket(serverPort);
        tcpListener = new ServerSocket(serverPort);
        tcpListener.setSoTimeout(TIMEOUT_MS);

        handler = new MessageHandler();

        System.out.println("Waiting for clients");
        eventLoop();
    }

    private static void eventLoop() throws IOException {
        while (true) {
            DatagramPacket packet = new DatagramPacket(new byte[MAX_SIZE], MAX_SIZE);
            socket.receive(packet); // blocks for next UDP datagram
            pool.execute(new ClientTask(packet)); // hand it to a worker thread
        }
    }

    private static class ClientTask implements Runnable {
        private final DatagramPacket request;

        // packet is stored as a copy
        ClientTask(DatagramPacket packet) {
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            this.request = new DatagramPacket(data, data.length,
                    packet.getAddress(), packet.getPort());
        }

        @Override
        public void run() {
            try {
                process(request);
            } catch (Exception ex) {
                System.err.println("Worker crashed: " + ex.getMessage());
            }
        }
    }

    private static void process(DatagramPacket req) throws Exception {
        String reqStr = Event.getPacketData(req);
        int clientPort = req.getPort();
        InetAddress clientAddress = req.getAddress();

        // extract info
        int command = Event.getAction(reqStr);
        String username = Event.getName(reqStr);
        String requestContent = Event.getContent(reqStr);

        // success return message
        byte[] response = Event.createEvent(command, SUCCESS, username, "Success").getBytes();

        if (command > LOGIN && !handler.isOnline(username)) {
            response = Event.createEvent(command, UNAUTHENTICATED, username, "Please Log in first").getBytes();
            socket.send(new DatagramPacket(response, response.length, clientAddress, clientPort));
            return;
        }

        String[] contentParts;
        String threadTitle;
        String newMessage;
        int messageNumber;
        String filename;
        Socket tcpSocket;
        switch (command) {
            case FIRST_CONN:
                System.out.println("Client authenticating");
                if (!handler.userExists(username)) {
                    System.out.println("New User");
                    response = Event.createEvent(command, SUCCESS, username,
                            "New User, enter password: ").getBytes();
                } else if (handler.isOnline(username)) {
                    System.out.println(username + " is already logged in");
                    response = Event.createEvent(command, FAILURE, username,
                            username + " has already logged in").getBytes();
                } else {
                    response = Event.createEvent(command, SUCCESS, username,
                            "Enter password: ").getBytes();
                }
                break;

            case LOGIN:
                String password = requestContent;
                if (!handler.userExists(username)) {
                    handler.addUser(username, password);
                    handler.setOnline(username, true);
                    System.out.println(username + " has successfully logged in");
                } else if (!handler.passwordOk(username, password)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Invalid login credentials (password)").getBytes();
                    System.out.println("Incorrect password");
                } else if (handler.isOnline(username)) {
                    System.out.println(username + " has already logged in");
                    response = Event.createEvent(command, FAILURE, username,
                            username + " has already logged in").getBytes();
                } else {
                    handler.setOnline(username, true);
                    System.out.println(username + " has successfully logged in");
                }
                break;

            case CRT:
                System.out.println(username + " issued a CRT command");
                threadTitle = requestContent;
                if (handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread " + threadTitle + " already exists").getBytes();
                    System.out.println("Thread " + threadTitle + " already exists");
                } else {
                    handler.createThread(threadTitle, username);
                    response = Event.createEvent(command, SUCCESS, username,
                            "Thread " + threadTitle + " created").getBytes();
                    System.out.println("Thread " + threadTitle + " created");
                }
                break;

            case MSG:
                System.out.println(username + " issued MSG command");
                contentParts = separateContent(requestContent, 2);
                threadTitle = contentParts[0];
                newMessage = contentParts[1];

                if (!handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread " + threadTitle + " does not exist").getBytes();
                    System.out.println("Thread " + threadTitle + " does not exist");
                } else {
                    handler.postMessage(threadTitle, username, newMessage);
                    response = Event.createEvent(command, SUCCESS, username,
                            "Message posted to " + threadTitle + " thread").getBytes();
                    System.out.println("Message posted to " + threadTitle + " thread");
                }
                break;

            case DLT:
                System.out.println(username + " issued DLT command");
                contentParts = separateContent(requestContent, 2);
                threadTitle = contentParts[0];
                messageNumber = Integer.parseInt(contentParts[1]);

                if (!handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread " + threadTitle + " does not exist").getBytes();
                    System.out.println("Thread " + threadTitle + " does not exist");
                } else {
                    int status = handler.deleteMessage(threadTitle, username, messageNumber);
                    if (status == 0) {
                        System.out.println("Message has been deleted");
                        response = Event.createEvent(command, SUCCESS, username,
                                "The message has been deleted").getBytes();
                    } else if (status == 1) {
                        System.out.println("Message cannot be deleted");
                        response = Event.createEvent(command, FAILURE, username,
                                "The message belongs to another user and cannot be deleted").getBytes();
                    } else if (status == 2) {
                        System.out.println("Message cannot be deleted");
                        response = Event.createEvent(command, FAILURE, username,
                                "The message of the number does not exist").getBytes();
                    }
                }
                break;

            case EDT:
                System.out.println(username + " issued EDT command");
                contentParts = separateContent(requestContent, 3);
                threadTitle = contentParts[0];
                messageNumber = Integer.parseInt(contentParts[1]);
                newMessage = contentParts[2];

                if (!handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread " + threadTitle + " does not exist").getBytes();
                    System.out.println("Thread " + threadTitle + " does not exist");
                } else {
                    int status = handler.editMessage(threadTitle, username, messageNumber, newMessage);
                    if (status == 0) {
                        System.out.println("Message has been edited");
                        response = Event.createEvent(command, SUCCESS, username,
                                "The message " + messageNumber + " in " + threadTitle + " has been edited").getBytes();
                    } else if (status == 1) {
                        System.out.println("Message cannot be edited");
                        response = Event.createEvent(command, FAILURE, username,
                                "The message belongs to another user and cannot be edited").getBytes();
                    } else if (status == 2) {
                        System.out.println("Message cannot be edited");
                        response = Event.createEvent(command, FAILURE, username,
                                "The message of the number does not exist").getBytes();
                    }
                }
                break;

            case LST:
                System.out.println(username + " issued LST command");
                String threadList = handler.listThreads();
                response = threadList.equals("No threads to list")
                        ? Event.createEvent(command, FAILURE, username, threadList).getBytes()
                        : Event.createEvent(command, SUCCESS, username, threadList).getBytes();
                break;

            case RDT:
                System.out.println(username + " issued RDT command");
                threadTitle = separateContent(requestContent, 1)[0];
                String threadContent = handler.readThread(threadTitle);

                if (!handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username, threadContent).getBytes();
                    System.out.println("Thread " + threadTitle + " does not exist");
                } else if (handler.threadIsEmpty(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username, threadContent).getBytes();
                    System.out.println("Thread " + threadTitle + " is empty");
                } else {
                    response = Event.createEvent(command, SUCCESS, username, threadContent).getBytes();
                    System.out.println("Thread " + threadTitle + " content sent");
                }
                break;

            case UPD:
                System.out.println(username + " issued UPD command");
                contentParts = separateContent(requestContent, 2);
                threadTitle = contentParts[0];
                filename = contentParts[1];

                if (!handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread " + threadTitle + " does not exist").getBytes();
                    System.out.println("Thread " + threadTitle + " does not exist");
                    break;
                } else if (handler.attachmentExists(threadTitle, filename)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "The file " + filename + " has already been posted in the Thread " + threadTitle)
                            .getBytes();
                    System.out
                            .println("The file " + filename + " has already been posted in the Thread " + threadTitle);
                    break;
                }

                response = Event.createEvent(command, SUCCESS, username, "ready").getBytes();
                socket.send(new DatagramPacket(response, response.length, clientAddress, clientPort));

                try {
                    tcpSocket = tcpListener.accept();
                } catch (SocketTimeoutException e) {
                    System.err.println("Stale DWN/UPD request – no TCP client arrived");
                    break; // send FAILURE or just abandon
                }
                handleReceiveUpload(tcpSocket, threadTitle, filename);
                tcpSocket.close();

                handler.uploadFile(threadTitle, username, filename);
                response = Event.createEvent(UPD, SUCCESS, username, filename + " successfully uploaded").getBytes();
                System.out.println(username + " has successfully uploaded file "
                        + filename + " to the " + threadTitle + " thread");
                break;

            case DWN:
                System.out.println(username + " issued DWN command");
                contentParts = separateContent(requestContent, 2);
                threadTitle = contentParts[0];
                filename = contentParts[1];

                if (!handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread " + threadTitle + " does not exist").getBytes();
                    break;
                }
                if (!handler.attachmentExists(threadTitle, filename)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "File does not exist in Thread " + threadTitle).getBytes();
                    break;
                }

                response = Event.createEvent(command, SUCCESS, username, "ready").getBytes();
                socket.send(new DatagramPacket(response, response.length, clientAddress, clientPort));

                try {
                    tcpSocket = tcpListener.accept();
                } catch (SocketTimeoutException e) {
                    System.err.println("Stale DWN/UPD request – no TCP client arrived");
                    break; // send FAILURE or just abandon
                }
                handleSendDownload(tcpSocket, threadTitle, filename);
                tcpSocket.close();
                System.out.println(filename + " downloaded from Thread " + threadTitle);

                response = Event.createEvent(command, SUCCESS, username,
                        filename + " successfully downloaded").getBytes();
                break;

            case RMV:
                System.out.println(username + " issued RMV command");
                threadTitle = requestContent;

                if (!handler.threadExists(threadTitle)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread " + threadTitle + " does not exist").getBytes();
                } else if (!handler.removeThread(threadTitle, username)) {
                    response = Event.createEvent(command, FAILURE, username,
                            "Thread was created by another user and cannot be removed").getBytes();
                } else {
                    response = Event.createEvent(command, SUCCESS, username,
                            "Thread " + threadTitle + " removed").getBytes();
                    System.out.println("Thread " + threadTitle + " removed");
                }
                break;

            case XIT:
                handler.setOnline(username, false);
                System.out.println(username + " has logged out");
                response = Event.createEvent(command, SUCCESS, username, "Goodbye").getBytes();
                break;

            default:
                response = Event.createEvent(command, FAILURE, username, "Invalid command").getBytes();
                break;
        }

        socket.send(new DatagramPacket(response, response.length,
                clientAddress, clientPort));
    }

    private static void handleReceiveUpload(Socket socket, String thread, String file) throws Exception {
        InputStream input = socket.getInputStream();
        BufferedInputStream bis = new BufferedInputStream(input);

        FileOutputStream fos = new FileOutputStream(handler.attachmentFilePath(thread, file));
        BufferedOutputStream bos = new BufferedOutputStream(fos);

        byte[] upload = new byte[MAX_FILE_SIZE];
        int length;
        while ((length = bis.read(upload)) != -1)
            bos.write(upload, 0, length);
        bos.flush();

        OutputStream output = socket.getOutputStream();
        String feedbackMessage = "The File " + file + " has been uploaded to Thread " + thread;
        output.write(feedbackMessage.getBytes());
        fos.close();
    }

    private static void handleSendDownload(Socket socket, String thread, String file) throws Exception {
        FileInputStream fis = new FileInputStream(handler.attachmentFilePath(thread, file));
        BufferedInputStream bis = new BufferedInputStream(fis);

        OutputStream output = socket.getOutputStream();
        BufferedOutputStream bos = new BufferedOutputStream(output);

        byte[] download = new byte[MAX_FILE_SIZE];
        int length;
        while ((length = bis.read(download)) != -1)
            bos.write(download, 0, length);

        bos.flush();
        socket.shutdownOutput();
        bis.close();
    }

    private static String[] separateContent(String content, int numParts) {
        String[] parts = content.split(" ", numParts);
        return parts.length == numParts ? parts : Arrays.copyOf(parts, numParts);
    }
}

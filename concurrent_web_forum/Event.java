import java.net.DatagramPacket;

public class Event {
    public static String createEvent(int action, int status, String username, String content) {
        return action + " " + status + " " + username + " " + content;
    }

    public static int getAction(String event) {
        String action_str = event.split(" ")[0];
        return Integer.parseInt(action_str);
    }

    public static int getStatus(String event) {
        String status_str = event.split(" ")[1];
        return Integer.parseInt(status_str);
    }

    public static String getName(String event) {
        return event.split(" ")[2];
    }

    public static String getContent(String event) {
        String[] parts = event.split(" ", 4);
        if (parts.length < 4)
            return ""; // no content
        return parts[3].trim(); // everything after the 3rd space
    }

    public static String getPacketData(DatagramPacket packet) throws Exception {
        return new String(packet.getData(), 0, packet.getLength()).trim();
    }

}

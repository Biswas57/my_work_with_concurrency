import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ThreadManager {

    private final Map<String, ForumThread> threads = new HashMap<>();
    private final Path directory = Paths.get("").toAbsolutePath(); // same as pwd

    public ThreadManager() {
    }

    public synchronized boolean createThread(User creator, String title) throws IOException {
        if (threads.containsKey(title)) {
            return false;
        }
        ForumThread thread = new ForumThread(title, creator, directory);
        thread.createFile();
        threads.put(title, thread);
        return true;
    }

    public synchronized String listTitles() {
        return threads.isEmpty() ? "No threads to list"
                : String.join(" ", threads.keySet());
    }

    public synchronized ForumThread get(String title) {
        return threads.get(title);
    }

    public synchronized boolean exists(String title) {
        return threads.containsKey(title);
    }

    public Path getServerDirectory() {
        return directory;
    }

    public synchronized boolean removeThread(String requester, String title) throws IOException {
        ForumThread thread = threads.get(title);
        if (thread == null || !thread.getCreator().name().equals(requester))
            return false;

        Files.deleteIfExists(thread.getPath());
        String prefix = title + "-";

        // this deletes all attachments associated with thread
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                f -> f.getFileName().toString().startsWith(prefix))) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
        }
        threads.remove(title);
        return true;
    }

    public synchronized String readFile(String title) {
        ForumThread thread = threads.get(title);
        if (thread == null)
            return "Thread " + thread + " not found";
        return thread.readFile();
    }

    /* ---------- thread message functions ---------- */

    public void post(String threadTitle, User author, String text) throws Exception {
        ForumThread thread = threads.get(threadTitle);
        if (thread != null) {
            thread.addMessage(text, author);
        }
    }

    public int deleteMessage(String threadTitle, String requester, int postNum) throws Exception {
        ForumThread thread = threads.get(threadTitle);
        if (thread != null) {
            return thread.deleteMessage(requester, postNum);
        }
        return 2; // thread not found
    }

    public int editMessage(String threadTitle, String requester, int postNum, String newText) throws Exception {
        ForumThread thread = threads.get(threadTitle);
        if (thread != null) {
            return thread.editMessage(requester, postNum, newText);
        }
        return 2; // thread not found
    }

    public void attach(String threadTitle, User author, String filename) {
        ForumThread thread = threads.get(threadTitle);
        if (thread != null) {
            try {
                thread.addAttachment(filename, author);
            } catch (IOException e) {
                System.out.println("Failed to attach file");
            }
        }
    }

    public boolean attachmentExists(String threadTitle, String fileName) {
        ForumThread thread = threads.get(threadTitle);
        if (thread == null) {
            return false;
        }

        Path filePath = directory.resolve(threadTitle + '-' + fileName);
        return Files.exists(filePath) && thread.hasFile(fileName);
    }
}

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ForumThread {

    private final String title;
    private final User creator;
    private final List<ThreadMessage> posts = new ArrayList<>();
    private final Path filePath;
    private int nextMsgNum = 1;

    public ForumThread(String title, User creator, Path root) {
        this.title = title;
        this.creator = creator;
        this.filePath = root.resolve(title);
    }

    public synchronized void addMessage(String text, User author) throws IOException {
        ThreadMessage p = new ThreadMessage(author, text,
                ThreadMessage.PostType.MESSAGE,
                nextMsgNum++);
        posts.add(p);
        appendToFile(p.toString());
    }

    public synchronized void addAttachment(String filename, User author) throws IOException {
        ThreadMessage message = new ThreadMessage(author, filename,
                ThreadMessage.PostType.ATTACHMENT, -1);
        posts.add(message);
        appendToFile(message.toString());
    }

    public synchronized int deleteMessage(String requester, int postNum) throws IOException {
        Optional<ThreadMessage> foundThread = posts.stream()
                .filter(post -> post.type() == ThreadMessage.PostType.MESSAGE
                        && post.number() == postNum)
                .findFirst();
        if (foundThread.isEmpty())
            return 2; // not found
        if (!foundThread.get().author().name().equals(requester))
            return 1; // not owner

        posts.remove(foundThread.get());
        renumber();
        rewriteFile();
        return 0;
    }

    public synchronized int editMessage(String requester, int num, String newText) throws IOException {
        for (ThreadMessage p : posts) {
            if (p.type() == ThreadMessage.PostType.MESSAGE && p.number() == num) {
                if (!p.author().name().equals(requester))
                    return 1;
                p.setText(newText);
                rewriteFile();
                return 0;
            }
        }
        return 2;
    }

    public String getTitle() {
        return title;
    }

    public User getCreator() {
        return creator;
    }

    public Path getPath() {
        return filePath;
    }

    public List<ThreadMessage> getPosts() {
        return posts;
    }

    public synchronized boolean hasFile(String filename) {
        return posts.stream().anyMatch(p -> p.type() == ThreadMessage.PostType.ATTACHMENT
                && p.text().equals(filename));
    }

    public synchronized String readFile() {
        if (posts.isEmpty())
            return "Thread " + title + " is empty";
        return posts.stream()
                .map(ThreadMessage::toString)
                .reduce((a, b) -> a + ';' + b)
                .orElse("");
    }

    void createFile() throws IOException {
        Files.writeString(filePath, creator.name() + System.lineSeparator());
    }

    private void appendToFile(String line) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(filePath,
                StandardOpenOption.APPEND)) {
            bw.write(line);
            bw.newLine();
        }
    }

    private void rewriteFile() throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(filePath,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(creator.name());
            bw.newLine();
            for (ThreadMessage p : posts) {
                bw.write(p.toString());
                bw.newLine();
            }
        }
    }

    private void renumber() {
        int n = 1;
        for (ThreadMessage p : posts) {
            if (p.type() == ThreadMessage.PostType.MESSAGE)
                p.setNumber(n++);
        }
        nextMsgNum = n;
    }
}

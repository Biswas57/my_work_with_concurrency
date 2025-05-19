import java.util.Objects;

public final class ThreadMessage {

    public enum PostType {
        MESSAGE, ATTACHMENT
    }

    private final User author;
    private final PostType type;
    private String text;
    private int number; // MESSAGE posts get a number >=1

    public ThreadMessage(User author, String text, PostType type, int num) {
        this.author = Objects.requireNonNull(author);
        this.text = text;
        this.type = type;
        this.number = num;
    }

    /* getters */
    public User author() {
        return author;
    }

    public PostType type() {
        return type;
    }

    public String text() {
        return text;
    }

    public int number() {
        return number;
    }

    /* setters used by Thread when editing / renumbering */
    void setText(String newText) {
        this.text = newText;
    }

    void setNumber(int num) {
        this.number = num;
    }

    @Override
    public String toString() {
        return (type == PostType.MESSAGE)
                ? number + " " + author.name() + ": " + text
                : author.name() + " uploaded " + text;
    }
}

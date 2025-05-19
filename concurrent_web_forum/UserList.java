import java.io.*;
import java.nio.file.*;
import java.util.*;

public class UserList {

    // wrap in a synchronizedList as a mutex guard against data races
    // learned from 6991 rust course lol
    private final List<User> users = Collections.synchronizedList(new ArrayList<>());
    private final Path credFile = Paths.get("").toAbsolutePath().resolve("credentials.txt");

    public UserList() {
        try (Scanner sc = new Scanner(credFile)) {
            while (sc.hasNextLine()) {
                String[] tokens = sc.nextLine().split(" ");
                if (tokens.length == 2)
                    users.add(new User(tokens[0], tokens[1]));
            }
        } catch (IOException e) {
            System.err.println("cannot read credentials.txt");
        }
    }

    // add new user and append to file
    public synchronized void add(String name, String pass) {
        users.add(new User(name, pass));
        try (BufferedWriter bw = Files.newBufferedWriter(credFile, StandardOpenOption.APPEND)) {
            bw.write(name + " " + pass);
            bw.newLine();
        } catch (IOException e) {
            System.err.println("cannot update credentials.txt");
        }
    }

    public synchronized boolean exists(String n) {
        return users.stream().anyMatch(u -> u.name().equals(n));
    }

    public synchronized User get(String name) {
        return users.stream()
                .filter(user -> user.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No such user: " + name));
    }

    public synchronized boolean validPassword(String name, String password) {
        return get(name).passwordOK(password);
    }

    public synchronized void setOnline(String name, boolean status) {
        get(name).setOnline(status);
    }

    public synchronized boolean isOnline(String name) {
        return get(name).isOnline();
    }

    public synchronized boolean anyOnline() {
        return users.stream().anyMatch(User::isOnline);
    }
}

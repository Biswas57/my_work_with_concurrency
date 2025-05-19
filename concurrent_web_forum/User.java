public class User {
    private final String name;
    private final String password;
    private boolean online = false;

    public User(String name, String password) {
        this.name = name;
        this.password = password;
    }

    public String name() {
        return name;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean o) {
        online = o;
    }

    public boolean passwordOK(String p) {
        return password.equals(p);
    }

    public String password() {
        return password;
    }
}

public class MessageHandler {

    private final UserList userList = new UserList();
    private final ThreadManager threadManager = new ThreadManager();;

    public MessageHandler() throws Exception {
    }

    /* ---------- authentication ---------- */

    public void addUser(String username, String password) {
        userList.add(username, password);
    }

    public boolean userExists(String username) {
        return userList.exists(username);
    }

    public boolean passwordOk(String username, String password) {
        return userList.validPassword(username, password);
    }

    public void setOnline(String username, boolean online) {
        userList.setOnline(username, online);
    }

    public boolean isOnline(String username) {
        return userList.isOnline(username);
    }

    public boolean anyUserOnline() {
        return userList.anyOnline();
    }

    /* ---------- threads ---------- */

    public boolean threadExists(String threadTitle) {
        return threadManager.exists(threadTitle);
    }

    public boolean createThread(String threadTitle, String creator) throws Exception {
        return threadManager.createThread(userList.get(creator), threadTitle);
    }

    public boolean removeThread(String threadTitle, String requester) throws Exception {
        return threadManager.removeThread(requester, threadTitle);
    }

    public String listThreads() {
        return threadManager.listTitles();
    }

    public String readThread(String threadTitle) {
        return threadManager.readFile(threadTitle);
    }

    public boolean threadIsEmpty(String threadTitle) {
        return threadManager.get(threadTitle).getPosts().isEmpty();
    }

    /* ---------- messages ---------- */

    public void postMessage(String threadTitle,
            String author,
            String messageText) throws Exception {
        threadManager.post(threadTitle, userList.get(author), messageText);
    }

    public int deleteMessage(String threadTitle,
            String requester,
            int messageNumber) throws Exception {
        return threadManager.deleteMessage(threadTitle, requester, messageNumber);
    }

    public int editMessage(String threadTitle,
            String requester,
            int messageNumber,
            String newText) throws Exception {
        return threadManager.editMessage(threadTitle, requester, messageNumber, newText);
    }

    /* ---------- files ---------- */

    public void uploadFile(String threadTitle,
            String uploader,
            String fileName) {
        threadManager.attach(threadTitle, userList.get(uploader), fileName);
    }

    public boolean attachmentExists(String threadTitle, String fileName) {
        return threadManager.attachmentExists(threadTitle, fileName);
    }

    public String attachmentFilePath(String threadTitle, String fileName) {
        return threadManager.getServerDirectory().toString()
                + "/" + threadTitle + "-" + fileName;
    }
}

package bots;

import messages.BotDocumentMessage;
import messages.BotMessage;
import messages.BotTextMessage;
import models.MessageBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.javatuples.Triplet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public interface Bot {
    String EVERY_CHANNEL = "*";

    // TODO: this string should be encapsulated in an external class
    String LOCATION_TO_URL = "https://www.openstreetmap.org/?mlat=%s&&mlon=%s";

    static void sendMessage(BotMessage message, List<Triplet<Bot, String, String>> sendToList,
                            String channelFrom, MessageBuilder builder) {
        for (Triplet<Bot, String, String> sendTo : sendToList) {
            if (sendTo.getValue2().equals(channelFrom) || channelFrom.equals(Bot.EVERY_CHANNEL)) {
                String msgId;
                if (message instanceof BotDocumentMessage) {
                    msgId = sendTo.getValue0().sendMessage(
                            (BotDocumentMessage) message, sendTo.getValue1());
                } else if (message instanceof BotTextMessage) {
                    msgId = sendTo.getValue0().sendMessage(
                            (BotTextMessage) message, sendTo.getValue1());
                } else {
                    System.err.println("Type of message not valid");
                    msgId = null;
                }

                if (null != msgId) {
                    builder.append(Integer.toString(sendTo.getValue0().hashCode()),
                            msgId, sendTo.getValue1());
                }
            }
        }

        builder.saveHistory();
    }

    // TODO: move "storeFile()" in an external class
    static String storeFile(byte[] data, String fileExtension,
                            Map<String, String> webserverConfig) throws URISyntaxException, IOException {
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        byte[] hash = digest.digest(data);
        String encoded = Base64.getEncoder().encodeToString(hash)
                .replace(File.separator, ""); // It prevents to create useless directories

        String filename = encoded + '.' + fileExtension;
        String contentFolder = webserverConfig.get("content-folder");

        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        Date date = new Date();
        String folder = dateFormat.format(date);

        String baseLocalPath;
        if (contentFolder.substring(contentFolder.length() - 1).equals(File.separator))
            baseLocalPath = contentFolder + folder;
        else
            baseLocalPath = contentFolder + File.separator + folder;
        if (!contentFolder.substring(contentFolder.length() - 1).equals(File.separator))
            baseLocalPath += File.separator;

        // Create the directory if not exist
        File directory = new File(baseLocalPath);
        directory.mkdirs();

        File file = new File(baseLocalPath + File.separator + filename);
        if (!file.exists()) {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            fos.write(data);
            try {
                fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        URIBuilder builder = new URIBuilder(webserverConfig.get("base-url"));
        builder.setPath(dateFormat.format(date) + '/' + filename);
        return builder.toString();
    }

    static List<Triplet<Bot, String, String[]>> askForUsers(
            String channelFrom,
            List<Triplet<Bot, String, String>> askToList) {
        List<Triplet<Bot, String, String[]>> allUsers = new ArrayList<>(askToList.size());
        for (Triplet<Bot, String, String> askTo : askToList) {
            if(askTo.getValue2().equals(channelFrom)) {
                String[] users = askTo.getValue0().getUsers(askTo.getValue1());
                allUsers.add(new Triplet(askTo.getValue0(), askTo.getValue1(), users));
            }
        }

        return allUsers;
    }

    boolean init(Map<String, String> configs, String[] channels,
                 Map<String, String> webserverConfig);

    void addBridge(Bot bot, String channelTo, String channelFrom);

    String sendMessage(BotTextMessage msg, String channelTo);

    String sendMessage(BotDocumentMessage msg, String channelTo);

    String[] getUsers(String channel);
}

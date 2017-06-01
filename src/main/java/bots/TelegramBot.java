package bots;

import bots.messages.BotImgMessage;
import bots.messages.BotMessage;
import bots.messages.BotTextMessage;
import org.apache.commons.io.IOUtils;
import org.javatuples.Triplet;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.TelegramBotsApi;
import org.telegram.telegrambots.api.methods.GetFile;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.methods.send.SendPhoto;
import org.telegram.telegrambots.api.objects.*;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;
import org.telegram.telegrambots.exceptions.TelegramApiRequestException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

// TODO: implement a way not to exceed bot messages limit
public final class TelegramBot extends TelegramLongPollingBot implements Bot {
    private static final String USERNAME_KEY = "username";
    private static final String TOKEN_KEY = "token";

    private static TelegramBotsApi telegramBotsApi;
    private final List<Triplet<Bot, String, String>> sendToList = new LinkedList<>();
    // You can't retreive users list, so it'll store users who wrote at least one time here
    private final Collection<String> users = new LinkedHashSet<>();
    private Map<String, String> configs;

    public TelegramBot() {
        this.configs = new LinkedHashMap<String, String>();

        if (TelegramBot.telegramBotsApi == null) {
            TelegramBot.telegramBotsApi = new TelegramBotsApi();
        }
    }

    public static void init() {
        ApiContextInitializer.init();
    }

    @Override
    public boolean init(Map<String, String> botConfigs, String[] channels,
                        Map<String, String> webserverConfig) {
        this.configs = botConfigs;

        try {
            TelegramBot.telegramBotsApi.registerBot(this);
        } catch (TelegramApiRequestException e) {
            return false;
        }
        return true;
    }

    @Override
    public void onUpdateReceived(Update update) {
        Message msg = update.getMessage();
        User user = msg.getFrom();
        this.users.add(user.getUserName());

        if (update.hasMessage()) {
            Chat chat = msg.getChat();
            String channelFrom = chat.getTitle();
            String authorNickname = user.getUserName();
            BotMessage message = new BotMessage(authorNickname, channelFrom, this);

            Message telegramMessage = update.getMessage();
            // Send image
            if (telegramMessage.hasPhoto()) {
                List<PhotoSize> photos = telegramMessage.getPhoto();

                PhotoSize photo = photos.get(photos.size() - 1);
                GetFile file = new GetFile();
                file.setFileId(photo.getFileId());
                try {
                    File img = this.getFile(file);
                    URL imgUrl = new URL(img.getFileUrl(this.configs.get(TelegramBot.TOKEN_KEY)));
                    HttpURLConnection httpConn = (HttpURLConnection) imgUrl.openConnection();
                    InputStream inputStream = httpConn.getInputStream();
                    byte[] output = IOUtils.toByteArray(inputStream);

                    String text = msg.getText();
                    BotTextMessage textMessage = new BotTextMessage(message, text);

                    String imgName = img.getFilePath();
                    String[] imgNameSplitted = imgName.split("\\.");
                    String extension = imgNameSplitted[imgNameSplitted.length - 1];

                    BotImgMessage imgMessage = new BotImgMessage(textMessage, extension, output);
                    Bot.sendMessage(imgMessage, this.sendToList, chat.getId().toString());

                    inputStream.close();
                    httpConn.disconnect();
                } catch (TelegramApiException | IOException e) {
                    System.err.println("Error loading the img received");
                }
            }

            // Send plain text
            else if (telegramMessage.hasText()) {
                String text = msg.getText();

                String[] commandSplitted = text.split("\\\\s+");
                if(text.startsWith("/users")) {
                    List<Triplet<Bot, String, String[]>> users = Bot.askForUsers(chat.getId().toString(), this.sendToList);
                    StringBuilder output = new StringBuilder();
                    for (Triplet<Bot, String, String[]> channel : users) {
                        output.append(channel.getValue0().getClass().getSimpleName())
                                .append("/")
                                .append(channel.getValue1())
                                .append(":\n");

                        for (String userTo : channel.getValue2()) {
                            output.append(userTo).append('\n');
                        }

                        output.append('\n');
                    }

                    SendMessage messageToSend = new SendMessage()
                            .setChatId(chat.getId())
                            .setText(output.toString());
                    try {
                        this.sendMessage(messageToSend);
                    } catch (TelegramApiException e) {
                        System.err.println("Failed to send message from TelegramBot");
                        e.printStackTrace();
                    }
                } else {
                    BotTextMessage textMessage = new BotTextMessage(message, text);
                    Bot.sendMessage(textMessage, this.sendToList, chat.getId().toString());
                }
            }
            // Send sticker
            else if (telegramMessage.getSticker() != null) {
                Sticker sticker = telegramMessage.getSticker();
                BotTextMessage textMessage = new BotTextMessage(message, sticker.getEmoji());
                Bot.sendMessage(textMessage, this.sendToList, chat.getId().toString());
            }
        }
    }

    @Override
    public String getBotUsername() {
        if (this.configs.containsKey(TelegramBot.USERNAME_KEY))
            return this.configs.get(TelegramBot.USERNAME_KEY);
        else
            return null;
    }

    @Override
    public String getBotToken() {
        if (this.configs.containsKey(TelegramBot.TOKEN_KEY))
            return this.configs.get(TelegramBot.TOKEN_KEY);
        else
            return null;
    }

    @Override
    public void addBridge(Bot bot, String channelTo, String channelFrom) {
        this.sendToList.add(Triplet.with(bot, channelTo, channelFrom));
    }

    @Override
    public void sendMessage(BotTextMessage msg, String channelTo) {
        SendMessage message = new SendMessage()
                .setChatId(channelTo)
                .setText(String.format("%s/%s/%s: %s",
                        msg.getBotFrom().getClass().getSimpleName(), msg.getChannelFrom(),
                        msg.getNicknameFrom(), msg.getText()));
        try {
            this.sendMessage(message);
        } catch (TelegramApiException e) {
            System.err.println(String.format("Failed to send message from %s to TelegramBot", msg.getBotFrom().getClass().getSimpleName()));
            e.printStackTrace();
        }
    }

    @Override
    public void sendMessage(BotImgMessage msg, String channelTo) {
        SendPhoto message = new SendPhoto()
                .setChatId(channelTo);

        if (msg.getText() != null)
            message.setCaption(String.format("%s/%s/%s: %s",
                    msg.getBotFrom().getClass().getSimpleName(), msg.getChannelFrom(),
                    msg.getNicknameFrom(), msg.getText()));
        else
            message.setCaption(String.format("%s/%s/%s",
                    msg.getBotFrom().getClass().getSimpleName(), msg.getChannelFrom(),
                    msg.getNicknameFrom()));

        InputStream imageStream = new ByteArrayInputStream(msg.getImg());
        message.setNewPhoto("img." + msg.getFileExtension(), imageStream);

        try {
            this.sendPhoto(message);
        } catch (TelegramApiException e) {
            System.err.println(String.format("Failed to send message from %s to TelegramBot", msg.getBotFrom().getClass().getSimpleName()));
            e.printStackTrace();
        }
    }

    @Override
    public String[] getUsers(String channel) {
        return this.users.toArray(new String[this.users.size()]);
    }
}

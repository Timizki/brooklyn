package bots;

import core.BotsController;
import messages.BotDocumentMessage;
import messages.BotMessage;
import messages.BotTextMessage;
import models.FileStorage;
import net.engio.mbassy.listener.Handler;
import net.engio.mbassy.listener.Invoke;
import org.javatuples.Triplet;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.helper.ChannelUserListChangeEvent;
import org.kitteh.irc.client.library.feature.AuthManager;
import org.kitteh.irc.client.library.feature.auth.SaslPlain;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class IrcBot implements Bot {
    private static final String USERNAME_KEY = "username";
    private static final String HOST_KEY = "host";
    private static final String PASSWORD_KEY = "password";
    private static final Pattern COMPILE = Pattern.compile("[\r\n]");
    private static final Pattern PATTERN = Pattern.compile("\\s+");
    private final Collection<String> blacklist = new LinkedHashSet<>();
    private final BotsController botsController = new BotsController();
    private final ResourceBundle resourceBundle = ResourceBundle.getBundle("resources");
    private Client client;
    private String botId;

    @Override
    public boolean init(String botId, Map<String, String> configs, String[] channels) {
        if (!configs.containsKey(USERNAME_KEY))
            return false;
        if (!configs.containsKey(HOST_KEY))
            return false;

        client = Client.builder().nick(configs.get(USERNAME_KEY))
                .serverHost(configs.get(HOST_KEY)).build();
        if (configs.containsKey(PASSWORD_KEY)) {
            AuthManager auth = client.getAuthManager();
            auth.addProtocol(new SaslPlain(client,
                    client.getIntendedNick(), configs.get(PASSWORD_KEY)));
        }

        client.getEventManager().registerEventListener(this);

        for (String channel : channels) {
            try {
                client.addChannel(channel);
            } catch (IllegalArgumentException e) {
                System.err.println(String.format("Invalid channel name '%s' on '%s'.", channel, configs.get(HOST_KEY)));
                e.printStackTrace();
            }
        }

        this.botId = botId;

        return true;
    }

    @Override
    public void addBridge(Bot bot, String channelTo, String channelFrom) {
        botsController.addBridge(bot, channelTo, channelFrom);
    }

    @Override
    public Optional<String> sendMessage(BotTextMessage msg, String channelTo) {
        String[] messagesWithoutNewline = COMPILE.split(msg.getText()); // IRC doesn't allow CR / LF
        for (String messageToken : messagesWithoutNewline) {
            client.sendMessage(channelTo, BotsController.messageFormatter(
                    msg.getBotFrom().getId(), msg.getChannelFrom(),
                    msg.getNicknameFrom(), Optional.ofNullable(messageToken)));
        }

        // There aren't reasons to store IRC messages
        return Optional.empty();
    }

    @Handler(delivery = Invoke.Asynchronously)
    private void onMessageReceived(ChannelMessageEvent message) {
        String authorNickname = message.getActor().getNick();
        // An user is removed from the blacklist only if writes at least one message
        this.blacklist.remove(authorNickname);

        String channelFrom = message.getChannel().getName();
        String text = message.getMessage();

        String[] textSpaceSplitted = PATTERN.split(text);
        if (2 == textSpaceSplitted.length &&
                textSpaceSplitted[0].equals(client.getNick()) &&
                "users".equals(textSpaceSplitted[1])) {
            List<Triplet<Bot, String, List<String>>> users = botsController.askForUsers(channelFrom);
            users.forEach(channel -> {
                StringBuilder output = new StringBuilder();
                output.append(channel.getValue0().getClass().getSimpleName())
                        .append('/')
                        .append(channel.getValue1())
                        .append(": ");

                channel.getValue2().forEach(userTo -> output.append(userTo).append(", "));

                output.delete(output.length() - 2, output.length() - 1);
                client.sendMessage(channelFrom, output.toString());
            });
        } else {
            BotMessage msg = new BotMessage(authorNickname, channelFrom, this);
            BotTextMessage textMessage = new BotTextMessage(msg, text);
            // An empty msg builder is passed. There aren't reasons to store IRC messages
            botsController.sendMessage(textMessage, channelFrom, Optional.empty());
        }
    }

    @Handler(delivery = Invoke.Asynchronously)
    public void onJoin(ChannelUserListChangeEvent event) {
        String authorNickname = event.getUser().getNick();

        if (!authorNickname.equals(client.getNick())) {
            Optional<Channel> channelFrom = event.getAffectedChannel();
            ChannelUserListChangeEvent.Change change = event.getChange();

            String channelFromName;
            if (channelFrom.isPresent())
                channelFromName = channelFrom.get().getName();
            else
                channelFromName = BotsController.EVERY_CHANNEL;

            if (blacklist.contains(authorNickname))
                return;

            String message;
            if (0 == change.compareTo(ChannelUserListChangeEvent.Change.JOIN)) {
                // An user is in the blacklist until it sends a message
                this.blacklist.add(authorNickname);
                message = MessageFormat.format(resourceBundle.getString("channel-joined"), authorNickname);
            } else
                message = MessageFormat.format(resourceBundle.getString(channelFrom.isPresent()
                        ? "channel-leaved" : "server-leaved"), authorNickname);

            BotMessage msg = new BotMessage(authorNickname, channelFromName, this);
            BotTextMessage textMessage = new BotTextMessage(msg, message);

            // A new, useless msg builder is passed. There aren't reasons to store IRC messages
            botsController.sendMessage(textMessage, channelFromName, Optional.empty());
        }
    }

    @Override
    public Optional<String> sendMessage(BotDocumentMessage msg, String channelTo) {
        try {
            String fileUrl = FileStorage.storeFile(msg.getDoc(), msg.getFileExtension());
            if (msg.getText() != null) {
                String[] text = COMPILE.split(msg.getText());

                if (text.length == 1) {
                    this.client.sendMessage(channelTo, BotsController.messageFormatter(
                            msg.getBotFrom().getId(), msg.getChannelFrom(), msg.getNicknameFrom(),
                            Optional.of(fileUrl + ' ' + text[0])));
                } else {
                    this.client.sendMessage(channelTo, BotsController.messageFormatter(
                            msg.getBotFrom().getId(),
                            msg.getChannelFrom(),
                            msg.getNicknameFrom(),
                            Optional.ofNullable(fileUrl)));
                    for (String messageToken : text) {
                        client.sendMessage(channelTo, BotsController.messageFormatter(
                                msg.getBotFrom().getId(), msg.getChannelFrom(),
                                msg.getNicknameFrom(), Optional.ofNullable(messageToken)));
                    }
                }
            } else {
                this.client.sendMessage(channelTo, BotsController.messageFormatter(
                        msg.getBotFrom().getId(), msg.getChannelFrom(),
                        msg.getNicknameFrom(), Optional.ofNullable(fileUrl)));
            }
        } catch (URISyntaxException | IOException e) {
            System.err.println("Error while storing the doc");
            e.printStackTrace();
        }

        // There aren't reasons to store IRC messages
        return Optional.empty();
    }

    @Override
    public void editMessage(BotTextMessage msg, String channelTo, String messageId) {
        String channelName = msg.getBotFrom().channelIdToName(msg.getChannelFrom());
        String[] messagesWithoutNewline = COMPILE.split(msg.getText()); // IRC doesn't allow CR / LF
        for (String messageToken : messagesWithoutNewline) {
            client.sendMessage(channelTo, BotsController.messageFormatter(
                    msg.getBotFrom().getId(), channelName, msg.getNicknameFrom(),
                    Optional.of(MessageFormat.format(resourceBundle.getString("message-edited"), messageToken))));
        }
    }

    @Override
    public List<String> getUsers(String channel) {
        if (client.getChannel(channel).isPresent()) {
            Channel ircChannel = client.getChannel(channel).get();
            List<User> listOfUsers = ircChannel.getUsers();

            return listOfUsers.stream()
                    .filter(user -> !user.getNick().equals(client.getNick()))
                    .map(User::getNick)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>(0);
    }

    @Override
    public String getId() {
        return botId;
    }

    @Override
    public String channelIdToName(String channelId) {
        // On IRC channelId and channelName are the same thing
        return channelId;
    }
}

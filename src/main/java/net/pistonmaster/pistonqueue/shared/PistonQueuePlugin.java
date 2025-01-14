/*
 * #%L
 * PistonQueue
 * %%
 * Copyright (C) 2021 AlexProgrammerDE
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package net.pistonmaster.pistonqueue.shared;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.pistonmaster.pistonqueue.shared.utils.ConfigOutdatedException;
import net.pistonmaster.pistonqueue.shared.utils.MessageType;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public interface PistonQueuePlugin {
    Optional<PlayerWrapper> getPlayer(UUID uuid);

    Optional<PlayerWrapper> getPlayer(String name);

    List<PlayerWrapper> getPlayers();

    Optional<ServerInfoWrapper> getServer(String name);

    void schedule(Runnable runnable, long delay, long period, TimeUnit unit);

    void info(String message);

    void warning(String message);

    void error(String message);

    List<String> getAuthors();

    String getVersion();

    File getDataDirectory();

    default void scheduleTasks(QueueListenerShared queueListener) {
        // Sends the position message and updates tab on an interval in chat
        schedule(() -> {
            if (!queueListener.isMainOnline())
                return;

            for (QueueType type : QueueType.values()) {
                sendMessage(type, Config.POSITION_MESSAGE_CHAT, MessageType.CHAT);
            }
        }, Config.POSITION_MESSAGE_DELAY, Config.POSITION_MESSAGE_DELAY, TimeUnit.MILLISECONDS);

        // Sends the position message and updates tab on an interval on hot bar
        schedule(() -> {
            if (!queueListener.isMainOnline())
                return;

            for (QueueType type : QueueType.values()) {
                sendMessage(type, Config.POSITION_MESSAGE_HOT_BAR, MessageType.ACTION_BAR);
            }
        }, Config.POSITION_MESSAGE_DELAY, Config.POSITION_MESSAGE_DELAY, TimeUnit.MILLISECONDS);

        // Updates the tab
        schedule(() -> {
            updateTab(QueueType.VETERAN, Config.HEADER_VETERAN, Config.FOOTER_VETERAN);
            updateTab(QueueType.PRIORITY, Config.HEADER_PRIORITY, Config.FOOTER_PRIORITY);
            updateTab(QueueType.REGULAR, Config.HEADER, Config.FOOTER);
        }, Config.QUEUE_MOVE_DELAY, Config.QUEUE_MOVE_DELAY, TimeUnit.MILLISECONDS);

        schedule(() -> {
            if (Config.PAUSE_QUEUE_IF_MAIN_DOWN && !queueListener.isMainOnline()) {
                QueueType.VETERAN.getQueueMap().forEach((UUID id, String str) -> getPlayer(id).ifPresent(value -> value.sendMessage(Config.PAUSE_QUEUE_IF_MAIN_DOWN_MESSAGE)));
                QueueType.PRIORITY.getQueueMap().forEach((UUID id, String str) -> getPlayer(id).ifPresent(value -> value.sendMessage(Config.PAUSE_QUEUE_IF_MAIN_DOWN_MESSAGE)));
                QueueType.REGULAR.getQueueMap().forEach((UUID id, String str) -> getPlayer(id).ifPresent(value -> value.sendMessage(Config.PAUSE_QUEUE_IF_MAIN_DOWN_MESSAGE)));
            }
        }, Config.POSITION_MESSAGE_DELAY, Config.POSITION_MESSAGE_DELAY, TimeUnit.MILLISECONDS);

        // Send plugin message
        schedule(this::sendCustomData, Config.QUEUE_MOVE_DELAY, Config.QUEUE_MOVE_DELAY, TimeUnit.MILLISECONDS);

        // Moves the queue when someone logs off the main server on an interval set in the config.yml
        schedule(queueListener::moveQueue, Config.QUEUE_MOVE_DELAY, Config.QUEUE_MOVE_DELAY, TimeUnit.MILLISECONDS);

        AtomicBoolean isFirstRun = new AtomicBoolean(true);
        // Checks the status of all the servers
        schedule(() -> {
            Optional<ServerInfoWrapper> serverInfoWrapper = getServer(Config.MAIN_SERVER);

            if (serverInfoWrapper.isPresent()) {
                if (serverInfoWrapper.get().isOnline()) {
                    if (!isFirstRun.get() && !queueListener.isMainOnline()) {
                        queueListener.setOnlineSince(Instant.now());
                    }

                    queueListener.setMainOnline(true);
                } else {
                    queueListener.setMainOnline(false);
                }
                isFirstRun.set(false);
            } else {
                warning("Main Server \"" + Config.MAIN_SERVER + "\" not set up!!! Check out: https://github.com/AlexProgrammerDE/PistonQueue/wiki/FAQ#server-not-set-up");
            }
        }, 500, Config.SERVER_ONLINE_CHECK_DELAY, TimeUnit.MILLISECONDS);

        schedule(() -> {
            Optional<ServerInfoWrapper> serverInfoWrapper = getServer(Config.QUEUE_SERVER);

            if (serverInfoWrapper.isPresent()) {
                if (serverInfoWrapper.get().isOnline()) {
                    queueListener.setQueueOnline(true);
                } else {
                    warning("Queue Server is down!!!");
                    queueListener.setQueueOnline(false);
                }
            } else {
                warning("Queue Server \"" + Config.QUEUE_SERVER + "\" not set up!!! Check out: https://github.com/AlexProgrammerDE/PistonQueue/wiki/FAQ#server-not-set-up");
            }
        }, 500, Config.SERVER_ONLINE_CHECK_DELAY, TimeUnit.MILLISECONDS);

        schedule(() -> {
            if (Config.ENABLE_AUTH_SERVER) {
                Optional<ServerInfoWrapper> serverInfoWrapper = getServer(Config.AUTH_SERVER);

                if (serverInfoWrapper.isPresent()) {
                    if (serverInfoWrapper.get().isOnline()) {
                        queueListener.setAuthOnline(true);
                    } else {
                        warning("Auth Server is down!!!");
                        queueListener.setAuthOnline(false);
                    }
                } else {
                    warning("Auth Server \"" + Config.AUTH_SERVER + "\" not set up!!! Check out: https://github.com/AlexProgrammerDE/PistonQueue/wiki/FAQ#server-not-set-up");
                }
            } else {
                queueListener.setAuthOnline(true);
            }
        }, 500, Config.SERVER_ONLINE_CHECK_DELAY, TimeUnit.MILLISECONDS);
    }

    default void sendMessage(QueueType queue, boolean bool, MessageType type) {
        if (bool) {
            AtomicInteger position = new AtomicInteger();

            for (Map.Entry<UUID, String> entry : new LinkedHashMap<>(queue.getQueueMap()).entrySet()) {
                getPlayer(entry.getKey()).ifPresent(player ->
                        player.sendMessage(type, Config.QUEUE_POSITION
                                .replace("%position%", String.valueOf(position.incrementAndGet()))
                                .replace("%total%", String.valueOf(queue.getQueueMap().size()))));
            }
        }
    }

    default void updateTab(QueueType queue, List<String> header, List<String> footer) {
        AtomicInteger position = new AtomicInteger();

        for (Map.Entry<UUID, String> entry : new LinkedHashMap<>(queue.getQueueMap()).entrySet()) {
            getPlayer(entry.getKey()).ifPresent(player -> {
                int incrementedPosition = position.incrementAndGet();

                player.sendPlayerListHeaderAndFooter(
                        header.stream().map(str -> replacePosition(str, incrementedPosition, queue)).collect(Collectors.toList()),
                        footer.stream().map(str -> replacePosition(str, incrementedPosition, queue)).collect(Collectors.toList()));
            });
        }
    }

    default String replacePosition(String text, int position, QueueType type) {
        if (type.getDurationToPosition().containsKey(position)) {
            Duration duration = type.getDurationToPosition().get(position);

            return SharedChatUtils.formatDuration(text, duration, position);
        } else {
            AtomicInteger biggestPositionAtomic = new AtomicInteger();
            AtomicReference<Duration> bestDurationAtomic = new AtomicReference<>(Duration.ZERO);

            type.getDurationToPosition().forEach((integer, instant) -> {
                if (integer > biggestPositionAtomic.get()) {
                    biggestPositionAtomic.set(integer);
                    bestDurationAtomic.set(instant);
                }
            });

            int biggestPosition = biggestPositionAtomic.get();
            Duration biggestDuration = bestDurationAtomic.get();

            int difference = position - biggestPosition;

            Duration imaginaryDuration = biggestDuration.plus(difference, ChronoUnit.MINUTES);

            return SharedChatUtils.formatDuration(text, imaginaryDuration, position);
        }
    }

    default void initializeReservationSlots() {
        schedule(() -> {
            Optional<ServerInfoWrapper> mainServer = getServer(Config.MAIN_SERVER);
            if (!mainServer.isPresent())
                return;

            Map<QueueType, AtomicInteger> map = new EnumMap<>(QueueType.class);

            for (PlayerWrapper player : mainServer.get().getConnectedPlayers()) {
                QueueType playerType = QueueType.getQueueType(player::hasPermission);

                if (map.containsKey(playerType)) {
                    map.get(playerType).incrementAndGet();
                } else {
                    map.put(playerType, new AtomicInteger(1));
                }
            }

            for (Map.Entry<QueueType, AtomicInteger> entry : map.entrySet()) {
                entry.getKey().getPlayersWithTypeInMain().set(entry.getValue().get());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @SuppressWarnings({"UnstableApiUsage"})
    default void sendCustomData() {
        List<PlayerWrapper> networkPlayers = getPlayers();

        if (networkPlayers == null || networkPlayers.isEmpty()) {
            return;
        }

        ByteArrayDataOutput outOnlineQueue = ByteStreams.newDataOutput();

        outOnlineQueue.writeUTF("onlineQueue");
        outOnlineQueue.writeInt(QueueType.REGULAR.getQueueMap().size());
        outOnlineQueue.writeInt(QueueType.PRIORITY.getQueueMap().size());
        outOnlineQueue.writeInt(QueueType.VETERAN.getQueueMap().size());

        ByteArrayDataOutput outOnlineMain = ByteStreams.newDataOutput();

        outOnlineQueue.writeUTF("onlineMain");
        outOnlineQueue.writeInt(QueueType.REGULAR.getPlayersWithTypeInMain().get());
        outOnlineQueue.writeInt(QueueType.PRIORITY.getPlayersWithTypeInMain().get());
        outOnlineQueue.writeInt(QueueType.VETERAN.getPlayersWithTypeInMain().get());

        Set<String> servers = new HashSet<>();
        networkPlayers.forEach(player -> {
            if (player.getCurrentServer().isPresent()) {
                servers.add(player.getCurrentServer().get());
            }
        });

        for (String server : servers) {
            getServer(server).ifPresent(serverInfoWrapper ->
                    serverInfoWrapper.sendPluginMessage("piston:queue", outOnlineQueue.toByteArray()));
            getServer(server).ifPresent(serverInfoWrapper ->
                    serverInfoWrapper.sendPluginMessage("piston:queue", outOnlineMain.toByteArray()));
        }
    }

    default void processConfig(File dataDirectory) {
        try {
            if (!dataDirectory.exists() && !dataDirectory.mkdir())
                return;

            File file = new File(dataDirectory, "config.yml");

            if (!file.exists()) {
                try {
                    Files.copy(Objects.requireNonNull(PistonQueuePlugin.class.getClassLoader().getResourceAsStream("proxyconfig.yml")), file.toPath());
                    loadConfig(dataDirectory);
                    return;
                } catch (IOException ie) {
                    ie.printStackTrace();
                }
            }

            loadConfig(dataDirectory);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    default void loadConfig(File dataDirectory) throws IOException {
        ConfigurationNode config = YamlConfigurationLoader.builder().path(new File(dataDirectory, "config.yml").toPath()).build().load();

        Arrays.asList(Config.class.getDeclaredFields()).forEach(it -> {
            try {
                it.setAccessible(true);

                String fieldName = it.getName().replace("_", "");
                if (List.class.isAssignableFrom(it.getType())) {
                    it.set(Config.class, config.node(fieldName).getList(String.class));
                } else {
                    it.set(Config.class, config.node(fieldName).get(it.getType()));
                }
            } catch (SecurityException | IllegalAccessException | SerializationException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                String[] text = e.getMessage().split(" ");
                String value = "";

                for (String str : text) {
                    if (str.toLowerCase().startsWith(Config.class.getPackage().getName().toLowerCase())) {
                        value = str;
                    }
                }

                String[] packageSplit = value.split("\\.");

                new ConfigOutdatedException(packageSplit[packageSplit.length - 1]).printStackTrace();
            }
        });
    }
}

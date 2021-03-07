package net.pistonmaster.pistonqueue.bungee.hooks;

import net.pistonmaster.pistonmotd.api.PlaceholderParser;
import net.pistonmaster.pistonmotd.api.PlaceholderUtil;
import net.pistonmaster.pistonqueue.bungee.QueueAPI;

public class PistonMOTDPlaceholder implements PlaceholderParser {
    public PistonMOTDPlaceholder() {
        PlaceholderUtil.registerParser(this);
    }

    @Override
    public String parseString(String s) {
        return s.replace("%pistonqueue_regular%", String.valueOf(QueueAPI.getRegularSize()))
                .replace("%pistonqueue_priority%", String.valueOf(QueueAPI.getPrioritySize()))
                .replace("%pistonqueue_veteran%", String.valueOf(QueueAPI.getVeteranSize()));
    }
}
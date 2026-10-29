package org.ahilgamegz.advancedban;

import java.util.Set;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.spicord.Spicord;
import org.spicord.api.addon.SimpleAddon;
import org.spicord.bot.DiscordBot;
import org.spicord.bot.command.SlashCommand;

import eu.mcdb.universal.Server;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/** Spicord slash-command bridge for AdvancedBan on Paper. */
public final class AdvancedBanAddon extends SimpleAddon {
    private static final Set<String> MODERATOR_ROLE_IDS = Set.of(
        "1527694892455690251",
        "1527694890488827944",
        "1528474720104743085",
        "1527000605162475651"
    );
    private static final String AUDIT_CHANNEL_ID = "1565273588561616916";
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern DURATION = Pattern.compile("(?i)(?:[0-9]+(?:mo|[smhdwy]))+");
    private static final int MAX_REASON_LENGTH = 200;

    private DiscordBot discordBot;

    public AdvancedBanAddon() {
        super("Spicord AdvancedBan", "spicord-advancedban", "ahilgamegz");
    }

    @Override
    public void onLoad(DiscordBot bot) {
        this.discordBot = bot;

        SlashCommand ban = bot.commandBuilder("ban", "Permanently ban a Minecraft player");
        addOption(ban, "player", "Minecraft username");
        addOption(ban, "reason", "Reason for the ban");
        setExecutor(ban, this::handleBan);
        bot.registerCommand(ban);

        SlashCommand tempban = bot.commandBuilder("tempban", "Temporarily ban a Minecraft player");
        addOption(tempban, "player", "Minecraft username");
        addOption(tempban, "duration", "Duration, for example 1d12h or 30m");
        addOption(tempban, "reason", "Reason for the temporary ban");
        setExecutor(tempban, this::handleTempban);
        bot.registerCommand(tempban);

        SlashCommand unban = bot.commandBuilder("unban", "Unban a Minecraft player");
        addOption(unban, "player", "Minecraft username");
        setExecutor(unban, this::handleUnban);
        bot.registerCommand(unban);
    }

    private void addOption(SlashCommand command, String name, String description) {
        try {
            command.getClass().getMethod("addOption", OptionType.class, String.class, String.class, boolean.class)
                .invoke(command, OptionType.STRING, name, description, true);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Incompatible Spicord SlashCommand.addOption API", error);
        }
    }

    private void setExecutor(SlashCommand command,
                             org.spicord.bot.command.SlashCommandExecutor executor) {
        try {
            command.getClass().getMethod("setExecutor", org.spicord.bot.command.SlashCommandExecutor.class)
                .invoke(command, executor);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Incompatible Spicord SlashCommand.setExecutor API", error);
        }
    }

    private void handleBan(SlashCommandInteractionEvent event) {
        if (!authorize(event)) return;
        String player = event.getOption("player").getAsString().trim();
        String reason = cleanReason(event.getOption("reason").getAsString());
        if (!validPlayer(event, player) || !validReason(event, reason)) return;
        dispatch(event, "ban", player, reason, "ban " + player + " " + reason,
            "Permanent ban", "Reason: " + reason);
    }

    private void handleTempban(SlashCommandInteractionEvent event) {
        if (!authorize(event)) return;
        String player = event.getOption("player").getAsString().trim();
        String duration = event.getOption("duration").getAsString().trim();
        String reason = cleanReason(event.getOption("reason").getAsString());
        if (!validPlayer(event, player) || !validReason(event, reason)) return;
        if (!DURATION.matcher(duration).matches()) {
            replyEphemeral(event, "Invalid duration. Use a value such as `30m`, `2h`, `7d`, or `1d12h`.");
            return;
        }
        dispatch(event, "tempban", player, reason, "tempban " + player + " " + duration + " " + reason,
            "Temporary ban", "Duration: " + duration + "\nReason: " + reason);
    }

    private void handleUnban(SlashCommandInteractionEvent event) {
        if (!authorize(event)) return;
        String player = event.getOption("player").getAsString().trim();
        if (!validPlayer(event, player)) return;
        dispatch(event, "unban", player, "", "unban " + player, "Unban", "Player unbanned: " + player);
    }

    private boolean authorize(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null || member.getRoles().stream().map(Role::getId).noneMatch(MODERATOR_ROLE_IDS::contains)) {
            replyEphemeral(event, "You don't have permission to use this moderation command.");
            return false;
        }
        return true;
    }

    private boolean validPlayer(SlashCommandInteractionEvent event, String player) {
        if (!PLAYER_NAME.matcher(player).matches()) {
            replyEphemeral(event, "Invalid Minecraft username. Use 1–16 letters, numbers, or underscores.");
            return false;
        }
        return true;
    }

    private boolean validReason(SlashCommandInteractionEvent event, String reason) {
        if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            replyEphemeral(event, "Please provide a reason between 1 and " + MAX_REASON_LENGTH + " characters.");
            return false;
        }
        return true;
    }

    private String cleanReason(String reason) {
        return reason.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
    }

    private void dispatch(SlashCommandInteractionEvent event, String action, String player,
                          String reason, String command, String auditTitle, String details) {
        deferEphemeral(event);
        try {
            Object pluginObject = Spicord.getInstance().getPlugin().getInstance();
            if (!(pluginObject instanceof Plugin paperPlugin)) {
                event.getHook().sendMessage("Couldn't access the Paper plugin scheduler. No command was run.").queue();
                audit("FAILED — could not obtain Paper plugin instance", event, action, player, details);
                return;
            }
            Bukkit.getScheduler().runTask(paperPlugin, () -> {
                try {
                    Server server = Server.getInstance();
                    if (server == null) {
                        sendFollowup(event, "The server command bridge is unavailable. No command was run.");
                        audit("FAILED — server bridge unavailable", event, action, player, details);
                        return;
                    }
                    boolean accepted = server.dispatchCommand(command);
                    String result = accepted
                        ? "Command dispatched to the server. Check the audit channel for the record."
                        : "The server did not accept the command. Check that AdvancedBan is installed and the command is valid.";
                    sendFollowup(event, result);
                    audit((accepted ? "Command dispatched" : "DISPATCH FAILED") + " — " + auditTitle,
                        event, action, player, details);
                } catch (Throwable error) {
                    sendFollowup(event, "An error occurred while dispatching the command. Check the server console.");
                    audit("ERROR while dispatching — " + error.getClass().getSimpleName(), event, action, player, details);
                    Spicord.getInstance().getPlugin().getLogger().warning(
                        "Spicord AdvancedBan failed to dispatch /" + action + ": " + error.getMessage());
                }
            });
        } catch (Throwable error) {
            sendFollowup(event, "Couldn't schedule the command on Paper. No command was run.");
            audit("FAILED — unable to schedule command", event, action, player, details);
            Spicord.getInstance().getPlugin().getLogger().warning(
                "Spicord AdvancedBan couldn't schedule /" + action + ": " + error.getMessage());
        }
    }

    private void replyEphemeral(SlashCommandInteractionEvent event, String message) {
        try {
            Object action = event.getClass().getMethod("reply", String.class).invoke(event, message);
            action.getClass().getMethod("setEphemeral", boolean.class).invoke(action, true);
            action.getClass().getMethod("queue").invoke(action);
        } catch (ReflectiveOperationException error) {
            Spicord.getInstance().getPlugin().getLogger().warning(
                "Spicord AdvancedBan couldn't reply to a slash command: " + error.getMessage());
        }
    }

    private void deferEphemeral(SlashCommandInteractionEvent event) {
        try {
            Object action = event.getClass().getMethod("deferReply", boolean.class).invoke(event, true);
            action.getClass().getMethod("queue").invoke(action);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Couldn't defer Discord interaction", error);
        }
    }

    private void sendFollowup(SlashCommandInteractionEvent event, String message) {
        try {
            Object hook = event.getClass().getMethod("getHook").invoke(event);
            Object action = hook.getClass().getMethod("sendMessage", String.class).invoke(hook, message);
            action.getClass().getMethod("queue").invoke(action);
        } catch (ReflectiveOperationException error) {
            Spicord.getInstance().getPlugin().getLogger().warning(
                "Spicord AdvancedBan couldn't send Discord follow-up: " + error.getMessage());
        }
    }

    private void audit(String status, SlashCommandInteractionEvent event, String actionName,
                       String player, String details) {
        try {
            Object channel = discordBot.getJda().getTextChannelById(AUDIT_CHANNEL_ID);
            if (channel == null) {
                Spicord.getInstance().getPlugin().getLogger().warning(
                    "Spicord AdvancedBan audit channel not found: " + AUDIT_CHANNEL_ID);
                return;
            }
            String moderator = event.getUser().getAsTag() + " (" + event.getUser().getId() + ")";
            String message = "**" + status + "**\n"
                + "Action: `" + actionName + "`\n"
                + "Player: `" + player + "`\n"
                + "Moderator: " + moderator + "\n"
                + details;
            Object action = channel.getClass().getMethod("sendMessage", String.class).invoke(channel, message);
            action.getClass().getMethod("queue").invoke(action);
        } catch (Throwable error) {
            Spicord.getInstance().getPlugin().getLogger().warning(
                "Spicord AdvancedBan couldn't write to audit channel: " + error.getMessage());
        }
    }
    }
            

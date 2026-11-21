package fi.alavesa.intercom.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fi.alavesa.intercom.IntercomPlugin;
import fi.alavesa.intercom.announce.AnnouncementManager;
import fi.alavesa.intercom.announce.Feedback;
import fi.alavesa.intercom.config.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /announce} (aliases {@code /pa}, {@code /intercom}), registered through Paper's Brigadier command
 * API - the norm on 26.x: the client gets the whole tree, so sub-commands and player names complete while
 * typing.
 * <p>
 * The bare command is visible to everyone and checks {@code intercom.announce} when it runs, so a player
 * without it gets the configured denial message instead of "unknown command". The sub-commands need
 * {@code intercom.admin} and are hidden from everyone else.
 */
public final class AnnounceCommand {

    private final IntercomPlugin plugin;

    public AnnounceCommand(IntercomPlugin plugin) { this.plugin = plugin; }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(tree().build(), "Make an announcement to every player", List.of("pa", "intercom")));
    }

    private static boolean admin(CommandSourceStack source) {
        return source.getSender().hasPermission(AnnouncementManager.PERMISSION_ADMIN);
    }

    private LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("announce")
            .executes(this::open)
            .then(Commands.literal("reload").requires(AnnounceCommand::admin).executes(this::reload))
            .then(Commands.literal("clear").requires(AnnounceCommand::admin).executes(this::clear))
            .then(Commands.literal("status").requires(AnnounceCommand::admin).executes(this::status))
            .then(Commands.literal("test").requires(AnnounceCommand::admin)
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(this::test)))
            .then(Commands.literal("open").requires(AnnounceCommand::admin)
                .then(Commands.argument("player", StringArgumentType.word()).suggests(this::suggestPlayers).executes(this::openFor)));
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(player.getName());
        }
        return builder.buildFuture();
    }

    private int open(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return 0;
        }
        plugin.dialog().open(player, ""); // checks the permission and says so when it is missing
        return Command.SINGLE_SUCCESS;
    }

    /** Puts the dialog on another player's screen. That player still needs intercom.announce to send anything. */
    private int openFor(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "player");
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", Messages.text("name", name));
            return 0;
        }
        plugin.dialog().open(target, "");
        return Command.SINGLE_SUCCESS;
    }

    private int test(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        AnnouncementManager.Result result = plugin.announcements().submit(sender, StringArgumentType.getString(context, "text"), true);
        Feedback.send(sender, result, plugin.messages(), plugin.settings());
        return result.accepted() ? Command.SINGLE_SUCCESS : 0;
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        plugin.reload();
        plugin.messages().send(context.getSource().getSender(), "reloaded",
            Messages.text("sha1", plugin.packSha1().isEmpty() ? "not built" : plugin.packSha1().substring(0, 8)),
            Messages.text("shader", plugin.shaderDescription()));
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandContext<CommandSourceStack> context) {
        int waiting = plugin.announcements().clear();
        plugin.messages().send(context.getSource().getSender(), "cleared", Messages.text("count", waiting));
        return Command.SINGLE_SUCCESS;
    }

    /** Technical read-out for whoever runs the server: not in messages.yml on purpose. */
    private int status(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        int online = Bukkit.getOnlinePlayers().size(), ready = 0;
        for (Player player : Bukkit.getOnlinePlayers()) if (plugin.delivery().bannerReady(player)) ready++;
        line(sender, "Intercom " + plugin.getPluginMeta().getVersion());
        line(sender, "Pack: " + (plugin.packSha1().isEmpty() ? "NOT BUILT" : "sha1 " + plugin.packSha1()) + ", delivery " + plugin.delivery().delivery().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        line(sender, "Text shader: " + plugin.shaderDescription());
        line(sender, "Players who see the banner: " + ready + " of " + online + " (the rest get the fallback)");
        line(sender, "Animation driver: " + plugin.settings().timing().driver().name().toLowerCase(Locale.ROOT).replace('_', '-')
            + ", queued: " + plugin.announcements().queued() + (plugin.banners().busy() ? ", one on screen" : ""));
        return Command.SINGLE_SUCCESS;
    }

    private static void line(CommandSender to, String text) {
        to.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }
}

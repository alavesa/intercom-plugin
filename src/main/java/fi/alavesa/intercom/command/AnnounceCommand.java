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
import fi.alavesa.intercom.pack.PackBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
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
            .then(Commands.literal("pack").executes(this::pack))
            .then(Commands.literal("test").requires(AnnounceCommand::admin)
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(this::test)))
            .then(Commands.literal("microphone").requires(this::builder).executes(this::microphone)
                .then(Commands.literal("place").executes(this::microphonePlace)))
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

    /** Gives the microphone item: right-click a block with it to place a microphone, sneak + right-click one to take it away. */
    /** The console, and players who may place and remove microphones (operators, unless microphone.op-only is off). */
    private boolean builder(CommandSourceStack source) {
        if (!admin(source)) return false;
        return !(source.getSender() instanceof Player player) || plugin.microphones().mayBuild(player);
    }

    private int microphone(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return 0;
        }
        player.getInventory().addItem(plugin.microphones().createItem()).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        plugin.messages().send(player, "microphone.given");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Places a microphone at the command's position, on the block grid, its front turned towards where the
     * command looks from - so "/announce microphone place" puts one at your feet, facing you, and
     * "/execute positioned 10 64 10 rotated 90 0 run announce microphone place" does it from the console.
     */
    private int microphonePlace(CommandContext<CommandSourceStack> context) {
        Location at = context.getSource().getLocation();
        Location spot = new Location(at.getWorld(), at.getBlockX() + 0.5, at.getBlockY(), at.getBlockZ() + 0.5);
        plugin.microphones().spawn(spot, Math.round((at.getYaw() + 180) / 45f) * 45f);
        plugin.messages().send(context.getSource().getSender(), "microphone.placed");
        return Command.SINGLE_SUCCESS;
    }

    private int test(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        // "/execute as <player> run announce test ..." announces in that player's name (prefix and all)
        CommandSender announcer = context.getSource().getExecutor() instanceof Player executor ? executor : sender;
        AnnouncementManager.Result result = plugin.announcements().submit(announcer, StringArgumentType.getString(context, "text"), true, true);
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
    /** A download link for the pack, for players whose client did not take it from the server: they add it by hand. */
    private int pack(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        URI uri = sender instanceof Player player ? plugin.delivery().uri(player) : null;
        if (uri == null) {
            // console, or no download address: the file itself can be handed out
            plugin.messages().send(sender, "pack.file", Messages.text("path", "plugins/Intercom/" + PackBuilder.ZIP_NAME));
            return Command.SINGLE_SUCCESS;
        }
        Component link = Component.text(uri.toString()).clickEvent(ClickEvent.openUrl(uri.toString()));
        plugin.messages().send(sender, "pack.link", Messages.tag("link", link));
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        int online = Bukkit.getOnlinePlayers().size(), ready = 0;
        for (Player player : Bukkit.getOnlinePlayers()) if (plugin.delivery().packLoaded(player)) ready++;
        line(sender, "Intercom " + plugin.getPluginMeta().getVersion());
        line(sender, "Pack: " + (plugin.packSha1().isEmpty() ? "NOT BUILT" : "sha1 " + plugin.packSha1()) + ", delivery " + plugin.delivery().delivery().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        line(sender, "Text shader: " + plugin.shaderDescription());
        line(sender, "Banner: " + (plugin.delivery().bannerReady() ? "shown to everyone" : "NOT available - everyone gets the fallback")
            + "; pack loaded by " + ready + " of " + online + " online");
        line(sender, "Animation driver: " + plugin.settings().timing().driver().name().toLowerCase(Locale.ROOT).replace('_', '-')
            + ", queued: " + plugin.announcements().queued() + (plugin.banners().busy() ? ", one on screen" : ""));
        return Command.SINGLE_SUCCESS;
    }

    private static void line(CommandSender to, String text) {
        to.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }
}

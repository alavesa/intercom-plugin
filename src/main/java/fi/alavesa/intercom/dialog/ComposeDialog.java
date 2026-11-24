package fi.alavesa.intercom.dialog;

import fi.alavesa.intercom.IntercomPlugin;
import fi.alavesa.intercom.announce.AnnouncementManager;
import fi.alavesa.intercom.announce.Feedback;
import fi.alavesa.intercom.config.Messages;
import fi.alavesa.intercom.config.Settings;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * The compose dialog (vanilla 26.x dialogs through Paper's Dialog API): a multi-line text box with a hard
 * length limit, Send and Cancel.
 * <p>
 * The dialog is built per player and sent directly - it is not a registry entry, so nothing has to exist in
 * a datapack. Send is a custom click action: the client answers with the action id and the text box's
 * content, which arrives as a {@link PlayerCustomClickEvent}. That packet can be forged by a modified client
 * at any time, with any text, so the handler treats it as untrusted input: the permission is checked again
 * and the text goes through the full validation in {@link AnnouncementManager#submit}. No per-dialog state is
 * kept on the server.
 */
public final class ComposeDialog implements Listener {

    private static final String INPUT_MESSAGE = "message";
    private static final Key ACTION_SEND = Key.key(Settings.NAMESPACE, "send");

    private final IntercomPlugin plugin;

    public ComposeDialog(IntercomPlugin plugin) { this.plugin = plugin; }

    /**
     * Opens the dialog for a player with intercom.announce; tells the player why not when they lack it.
     *
     * @param draft text to start with (what they typed before a refused attempt), or ""
     */
    public void open(Player player, String draft) {
        if (!AnnouncementManager.mayAnnounce(player)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        show(player, draft);
    }

    /** Shows the dialog without asking who the player is - for callers that have checked (the microphone). */
    public void show(Player player, String draft) {
        Messages messages = plugin.messages();
        Settings settings = plugin.settings();
        int max = settings.announcement().maxCharacters();
        // shown to everyone; without the pack the textured panel and buttons would be boxes, so those players get
        // the same dialog drawn by Minecraft itself
        boolean fancy = settings.dialog().fancy() && plugin.delivery().packLoaded(player);
        DialogSkin skin = new DialogSkin(plugin.fontWidths());

        Component hint = orEmpty(messages.component("dialog.hint", Messages.text("max", max)));
        Component send = orEmpty(messages.component("dialog.send")), cancel = orEmpty(messages.component("dialog.cancel"));

        Component panel = fancy ? skin.body(hint) : null;

        // the box refuses to take more than max characters; a draft is cut to that so the dialog always opens
        String initial = draft == null ? "" : draft;
        if (initial.codePointCount(0, initial.length()) > max) initial = initial.substring(0, initial.offsetByCodePoints(0, max));
        // line breaks are kept as typed (each line is a paragraph of the banner)
        TextDialogInput input = DialogInput.text(INPUT_MESSAGE, orEmpty(messages.component("dialog.input-label")))
            .width(DialogSkin.INPUT_WIDTH)
            // in the textured dialog the panel is laid out for a box without a label line above it
            .labelVisible(!fancy)
            .initial(initial)
            .maxLength(max)
            .multiline(TextDialogInput.MultilineOptions.create(settings.dialog().inputMaxLines(), settings.dialog().inputHeight()))
            .build();

        ActionButton.Builder sendButton = ActionButton.builder(fancy ? skin.buttonLabel(DialogSkin.GLYPH_SEND, send) : send)
            .width(DialogSkin.BUTTON_WIDTH)
            .action(DialogAction.customClick(ACTION_SEND, null));
        Component tooltip = messages.component("dialog.send-tooltip");
        if (tooltip != null) sendButton.tooltip(tooltip);
        // no action: the button (and Escape, which presses it) just closes the dialog
        ActionButton cancelButton = ActionButton.builder(fancy ? skin.buttonLabel(DialogSkin.GLYPH_CANCEL, cancel) : cancel)
            .width(DialogSkin.BUTTON_WIDTH)
            .build();

        DialogBase base = DialogBase.builder(orEmpty(messages.component("dialog.title")))
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(List.of(DialogBody.plainMessage(panel == null ? hint : panel, DialogSkin.BODY_WIDTH)))
            .inputs(List.of(input))
            .build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.confirmation(sendButton.build(), cancelButton))));
    }

    private static Component orEmpty(Component component) { return component == null ? Component.empty() : component; }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().equals(ACTION_SEND)) return;
        // dialogs can also be shown during the configuration phase; ours never is, so anything else is not ours
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        DialogResponseView view = event.getDialogResponseView();
        String text = view == null ? null : view.getText(INPUT_MESSAGE);

        // a microphone lets a player announce without intercom.announce - when the SERVER saw them open it there
        boolean atMicrophone = plugin.microphones().atMicrophone(player);
        AnnouncementManager.Result result = plugin.announcements().submit(player, text == null ? "" : text, false, atMicrophone);
        Feedback.send(player, result, plugin.messages(), plugin.settings());
        switch (result.outcome()) {
            // something the player can fix by editing: give the dialog back with what they wrote
            case BLANK, TOO_LONG, DOES_NOT_FIT -> show(player, result.cleaned());
            case SENT, QUEUED -> plugin.microphones().forget(player);
            default -> { }
        }
    }
}

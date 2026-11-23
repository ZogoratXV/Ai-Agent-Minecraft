package dev.tessera.event;

import dev.tessera.Tessera;
import dev.tessera.bukkit.TesseraBlock;
import dev.tessera.bukkit.TesseraPlayer;
import dev.tessera.bukkit.TesseraServer;
import dev.tessera.bukkit.TesseraWorld;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Traduce gli eventi Fabric/vanilla in eventi Bukkit. Join/quit e piazzamento blocchi passano
 * dalle mixin in {@code dev.tessera.mixin}, che chiamano i metodi statici qui sotto.
 */
public final class EventBridge {

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> JoinQuit.onJoin(handler.player));

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> onChat(sender, message.signedContent()));

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer sp) || !Tessera.isRunning()) return true;
            BlockBreakEvent event = new BlockBreakEvent(block(sp.level(), pos), bukkit(sp));
            call(event);
            return !event.isCancelled();
        });

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !Tessera.isRunning()) return InteractionResult.PASS;
            return interact(sp, Action.RIGHT_CLICK_BLOCK, hand, block(sp.level(), hit.getBlockPos()), face(hit.getDirection()));
        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!(player instanceof ServerPlayer sp) || !Tessera.isRunning()) return InteractionResult.PASS;
            return interact(sp, Action.LEFT_CLICK_BLOCK, hand, block(sp.level(), pos), face(direction));
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer sp) || !Tessera.isRunning()) return InteractionResult.PASS;
            return interact(sp, Action.RIGHT_CLICK_AIR, hand, null, BlockFace.SELF);
        });
    }

    private static boolean onChat(ServerPlayer sender, String text) {
        if (!Tessera.isRunning()) return true;
        TesseraServer server = Tessera.bukkit();
        Set<Player> recipients = new HashSet<>(server.getOnlinePlayers());
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, bukkit(sender), text, recipients);
        call(event);
        if (event.isCancelled()) return false;
        boolean changed = !event.getMessage().equals(text) || !event.getFormat().equals("<%1$s> %2$s")
                || event.getRecipients().size() != recipients.size();
        if (!changed) return true;
        // Un plugin ha cambiato formato, testo o destinatari: inviamo noi il messaggio (non firmato).
        String formatted = String.format(event.getFormat(), event.getPlayer().getDisplayName(), event.getMessage());
        for (Player p : event.getRecipients()) p.sendMessage(formatted);
        server.getConsoleSender().sendMessage(formatted);
        return false;
    }

    private static InteractionResult interact(ServerPlayer sp, Action action, InteractionHand hand,
            TesseraBlock block, BlockFace face) {
        var stack = sp.getItemInHand(hand);
        ItemStack item = stack.isEmpty() ? null : new dev.tessera.bukkit.TesseraItemStack(stack.copy());
        PlayerInteractEvent event = new PlayerInteractEvent(bukkit(sp), action, item, block, face,
                hand == InteractionHand.MAIN_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
        call(event);
        return event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY
                && event.useItemInHand() == org.bukkit.event.Event.Result.DENY
                ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    static Material material(net.minecraft.world.item.ItemStack stack) {
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        Material m = Material.matchMaterial(id.toString());
        return m == null ? Material.AIR : m;
    }

    static TesseraPlayer bukkit(ServerPlayer player) {
        return Tessera.bukkit().wrap(player);
    }

    static TesseraBlock block(ServerLevel level, BlockPos pos) {
        TesseraWorld world = Tessera.bukkit().wrap(level);
        return new TesseraBlock(world, pos);
    }

    static BlockFace face(Direction direction) {
        return switch (direction) {
            case UP -> BlockFace.UP;
            case DOWN -> BlockFace.DOWN;
            case NORTH -> BlockFace.NORTH;
            case SOUTH -> BlockFace.SOUTH;
            case EAST -> BlockFace.EAST;
            case WEST -> BlockFace.WEST;
        };
    }

    static void call(org.bukkit.event.Event event) {
        Tessera.bukkit().getPluginManager().callEvent(event);
    }

    private EventBridge() {
    }
}

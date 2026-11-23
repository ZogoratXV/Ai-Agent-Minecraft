package dev.tessera.event;

import dev.tessera.Tessera;
import dev.tessera.bukkit.TesseraBlock;
import dev.tessera.bukkit.TesseraBlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** BlockPlaceEvent, chiamato dalla mixin su BlockItem#placeBlock prima che il blocco venga messo. */
public final class BlockPlace {

    /** @return true se un plugin ha annullato il piazzamento */
    public static boolean cancelled(BlockPlaceContext context, BlockState newState) {
        if (!Tessera.isRunning() || !(context.getPlayer() instanceof ServerPlayer sp)) return false;
        TesseraBlock placed = EventBridge.block(sp.level(), context.getClickedPos());
        TesseraBlock against = EventBridge.block(sp.level(), context.getClickedPos().relative(context.getClickedFace().getOpposite()));
        TesseraBlockState replaced = new TesseraBlockState(placed, placed.vanillaState());
        var stack = context.getItemInHand();
        ItemStack item = new dev.tessera.bukkit.TesseraItemStack(stack.copy());
        EquipmentSlot hand = context.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        BlockPlaceEvent event = new BlockPlaceEvent(placed, replaced, against, item, EventBridge.bukkit(sp), true, hand);
        EventBridge.call(event);
        if (event.isCancelled() || !event.canBuild()) {
            // Il client ha già previsto il blocco: rimandiamo lo stato reale.
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(sp.level(), context.getClickedPos()));
            sp.containerMenu.sendAllDataToRemote();
            return true;
        }
        return false;
    }

    private BlockPlace() {
    }
}

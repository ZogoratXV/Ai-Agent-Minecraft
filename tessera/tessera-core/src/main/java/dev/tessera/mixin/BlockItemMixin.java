package dev.tessera.mixin;

import dev.tessera.event.BlockPlace;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BlockPlaceEvent: se annullato, il blocco non viene piazzato e l'oggetto non viene consumato. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {

    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void tessera$blockPlace(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!context.getLevel().isClientSide() && BlockPlace.cancelled(context, state)) {
            cir.setReturnValue(false);
        }
    }
}

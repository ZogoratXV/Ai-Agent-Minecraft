package dev.rpgbridge.core.mixin.classlock;

import dev.rpgbridge.core.classlock.ClassLock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocco per classe: l'oggetto non si usa (tasto destro in aria, su un blocco o su un'entità: archi, scudi,
 * bacchette, armature indossate col tasto destro...). Le interazioni con i blocchi senza oggetto restano possibili.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackUseLockMixin {
	@Inject(method = "use", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$classLockUse(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (player instanceof ServerPlayer sp && !ClassLock.check(sp, (ItemStack) (Object) this)) cir.setReturnValue(InteractionResult.FAIL);
	}

	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$classLockUseOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (context.getPlayer() instanceof ServerPlayer sp && !ClassLock.check(sp, (ItemStack) (Object) this)) cir.setReturnValue(InteractionResult.FAIL);
	}

	@Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$classLockInteract(Player player, LivingEntity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (player instanceof ServerPlayer sp && !ClassLock.check(sp, (ItemStack) (Object) this)) cir.setReturnValue(InteractionResult.FAIL);
	}
}

package dev.rpgbridge.core.mixin.classlock;

import dev.rpgbridge.core.classlock.ClassLock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocco per classe: niente attacco con un'arma di un'altra classe (attacco vanilla e Better Combat). */
@Mixin(Player.class)
public abstract class PlayerAttackLockMixin {
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void rpgbridge$classLockAttack(Entity target, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player && !ClassLock.check(player, player.getMainHandItem())) ci.cancel();
	}
}

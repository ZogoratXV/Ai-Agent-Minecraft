package dev.rpgbridge.core.mixin.classlock;

import dev.rpgbridge.core.classlock.ClassLock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.spell_engine.network.Packets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocco per classe: Spell Engine non avvia né prosegue spell (e attacchi con spell) se un oggetto nelle mani del
 * giocatore è riservato a un'altra classe. Le spell di armature e accessori sono coperte perché quegli oggetti non
 * restano indossati (ClassLockEquipmentMixin).
 */
@Pseudo
@Mixin(targets = "net.spell_engine.network.ServerNetwork", remap = false)
public abstract class SpellCastLockMixin {
	@Unique
	private static boolean rpgbridge$blocked(ServerPlayer player) {
		if (!ClassLock.allowed(player, player.getMainHandItem())) {
			ClassLock.notify(player, player.getMainHandItem());
			return true;
		}
		if (!ClassLock.allowed(player, player.getOffhandItem())) {
			ClassLock.notify(player, player.getOffhandItem());
			return true;
		}
		return false;
	}

	/** Spell da apprendere per blocco (sezione "spell" di classi.yml): serve il sigillo del blocco. */
	@Unique
	private static boolean rpgbridge$spellBlocked(ServerPlayer player, String spellId) {
		if (ClassLock.spellAllowed(player, spellId)) return false;
		ClassLock.notifySpell(player, spellId);
		return true;
	}

	@Inject(method = "handleCastRequest", at = @At("HEAD"), cancellable = true, require = 0)
	private static void rpgbridge$castRequest(Packets.CastRequest packet, MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
		if (rpgbridge$blocked(player) || rpgbridge$spellBlocked(player, String.valueOf(packet.spellId()))) ci.cancel();
	}

	@Inject(method = "handleCastInput", at = @At("HEAD"), cancellable = true, require = 0)
	private static void rpgbridge$castInput(Packets.CastInput packet, MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
		if (rpgbridge$blocked(player) || rpgbridge$spellBlocked(player, String.valueOf(packet.spellId()))) ci.cancel();
	}

	@Inject(method = "handleAttackPerform", at = @At("HEAD"), cancellable = true, require = 0)
	private static void rpgbridge$attackPerform(Packets.AttackPerform packet, MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
		if (rpgbridge$blocked(player)) ci.cancel();
	}
}

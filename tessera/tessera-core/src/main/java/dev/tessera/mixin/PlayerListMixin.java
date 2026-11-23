package dev.tessera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.tessera.event.JoinQuit;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Trattiene il messaggio "X è entrato" per lasciarlo decidere al PlayerJoinEvent. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {

    @WrapOperation(
            method = "placeNewPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void tessera$joinMessage(PlayerList list, Component message, boolean overlay, Operation<Void> original,
            Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        if (!JoinQuit.captureJoinMessage(player, message)) {
            original.call(list, message, overlay);
        }
    }
}

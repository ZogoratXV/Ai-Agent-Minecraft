package dev.tessera.bukkit;

import dev.tessera.bridge.Text;
import dev.tessera.stub.StubPlayer;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Giocatore Bukkit. Tiene solo UUID e nome: l'entità vanilla viene cercata ogni volta, così il
 * wrapper resta valido anche dopo un respawn o un cambio di dimensione (che ricreano ServerPlayer).
 */
public final class TesseraPlayer extends StubPlayer {

    private final TesseraServer server;
    private final UUID uuid;
    private final String name;
    private @Nullable PermissibleBase perms;

    TesseraPlayer(TesseraServer server, UUID uuid, String name) {
        this.server = server;
        this.uuid = uuid;
        this.name = name;
    }

    @Override
    protected String tesseraStubName() {
        return "Player";
    }

    public @Nullable ServerPlayer handle() {
        return server.handle().getPlayerList().getPlayer(uuid);
    }

    private ServerPlayer require() {
        ServerPlayer p = handle();
        if (p == null) throw new IllegalStateException(name + " non è online");
        return p;
    }

    private PermissibleBase perms() {
        if (perms == null) perms = new PermissibleBase(this);
        return perms;
    }

    // ---------------------------------------------------------------- identità

    @Override
    public String getName() {
        return name;
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public EntityType getType() {
        return EntityType.PLAYER;
    }

    @Override
    public int getEntityId() {
        return require().getId();
    }

    @Override
    public boolean isOnline() {
        return handle() != null;
    }

    @Override
    public boolean isValid() {
        ServerPlayer p = handle();
        return p != null && p.isAlive();
    }

    @Override
    public boolean isDead() {
        ServerPlayer p = handle();
        return p == null || p.isDeadOrDying();
    }

    @Override
    public Component displayName() {
        return Text.fromVanilla(require().getDisplayName());
    }

    @Override
    public String getDisplayName() {
        return Text.toLegacy(displayName());
    }

    @Override
    public Component name() {
        return Component.text(name);
    }

    // ---------------------------------------------------------------- messaggi

    @Override
    public void sendMessage(String message) {
        sendMessage(Text.legacy(message));
    }

    @Override
    public void sendMessage(String[] messages) {
        for (String m : messages) sendMessage(m);
    }

    @Override
    public void sendRawMessage(String message) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(Component message) {
        ServerPlayer p = handle();
        if (p != null) p.sendSystemMessage(Text.toVanilla(message));
    }

    @Override
    public void sendActionBar(Component message) {
        ServerPlayer p = handle();
        if (p != null) p.sendOverlayMessage(Text.toVanilla(message));
    }

    @Override
    public void chat(String message) {
        if (message.startsWith("/")) {
            performCommand(message.substring(1));
            return;
        }
        // Semplificazione: messaggio di sistema "<nome> testo" (niente firma crittografica della chat).
        server.broadcast(Component.text("<" + name + "> " + message));
    }

    @Override
    public boolean performCommand(String command) {
        return server.dispatchCommand(this, command);
    }

    @Override
    public void kickPlayer(String message) {
        kick(Text.legacy(message));
    }

    @Override
    public void kick(Component message) {
        ServerPlayer p = handle();
        if (p != null) p.connection.disconnect(Text.toVanilla(message));
    }

    @Override
    public void kick() {
        kick(Component.translatable("multiplayer.disconnect.kicked"));
    }

    // ---------------------------------------------------------------- posizione

    @Override
    public World getWorld() {
        return server.wrap(require().level());
    }

    @Override
    public Location getLocation() {
        ServerPlayer p = require();
        return new Location(getWorld(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }

    @Override
    public @Nullable Location getLocation(@Nullable Location loc) {
        if (loc == null) return null;
        ServerPlayer p = require();
        loc.setWorld(getWorld());
        loc.set(p.getX(), p.getY(), p.getZ());
        loc.setYaw(p.getYRot());
        loc.setPitch(p.getXRot());
        return loc;
    }

    @Override
    public boolean teleport(Location location) {
        ServerPlayer p = handle();
        if (p == null) return false;
        ServerLevel level = location.getWorld() instanceof TesseraWorld w ? w.handle() : p.level();
        return p.teleportTo(level, location.getX(), location.getY(), location.getZ(), Set.of(),
                location.getYaw(), location.getPitch(), true);
    }

    @Override
    public boolean teleport(Location location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        return teleport(location);
    }

    @Override
    public boolean teleport(Entity destination) {
        return teleport(destination.getLocation());
    }

    // ---------------------------------------------------------------- stato

    @Override
    public GameMode getGameMode() {
        return GameMode.valueOf(require().gameMode().name());
    }

    @Override
    public void setGameMode(GameMode mode) {
        require().setGameMode(GameType.valueOf(mode.name()));
    }

    @Override
    public double getHealth() {
        return require().getHealth();
    }

    @Override
    public void setHealth(double health) {
        require().setHealth((float) health);
    }

    @Override
    public int getFoodLevel() {
        return require().getFoodData().getFoodLevel();
    }

    @Override
    public void setFoodLevel(int value) {
        require().getFoodData().setFoodLevel(value);
    }

    @Override
    public int getLevel() {
        return require().experienceLevel;
    }

    // ---------------------------------------------------------------- permessi

    @Override
    public boolean isOp() {
        ServerPlayer p = handle();
        return p != null && server.handle().getPlayerList().isOp(p.nameAndId());
    }

    @Override
    public void setOp(boolean value) {
        ServerPlayer p = require();
        if (value) server.handle().getPlayerList().op(p.nameAndId());
        else server.handle().getPlayerList().deop(p.nameAndId());
        perms().recalculatePermissions();
    }

    @Override
    public boolean isPermissionSet(String perm) {
        return perms().isPermissionSet(perm);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return perms().isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String perm) {
        return perms().hasPermission(perm);
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return perms().hasPermission(perm);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return perms().addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return perms().addAttachment(plugin);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        perms().removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        perms().recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return perms().getEffectivePermissions();
    }

    // ---------------------------------------------------------------- uguaglianza

    @Override
    public boolean equals(Object o) {
        return o instanceof TesseraPlayer other && other.uuid.equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return "TesseraPlayer{" + name + "}";
    }
}

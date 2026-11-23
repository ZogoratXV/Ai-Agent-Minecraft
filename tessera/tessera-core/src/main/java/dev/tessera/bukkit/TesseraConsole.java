package dev.tessera.bukkit;

import dev.tessera.bridge.Text;
import dev.tessera.stub.StubConsoleCommandSender;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** La console del server: ha tutti i permessi e scrive nel log. */
public final class TesseraConsole extends StubConsoleCommandSender {

    private static final Logger LOGGER = LoggerFactory.getLogger("Console");
    private final TesseraServer server;
    private PermissibleBase perms;

    TesseraConsole(TesseraServer server) {
        this.server = server;
    }

    @Override
    protected String tesseraStubName() {
        return "ConsoleCommandSender";
    }

    private PermissibleBase perms() {
        if (perms == null) perms = new PermissibleBase(this);
        return perms;
    }

    @Override
    public String getName() {
        return "CONSOLE";
    }

    @Override
    public Component name() {
        return Component.text(getName());
    }

    @Override
    public Server getServer() {
        return server;
    }

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
        LOGGER.info(Text.plain(message));
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(boolean value) {
        throw new UnsupportedOperationException("Non si può cambiare lo stato op della console");
    }

    @Override
    public boolean hasPermission(String name) {
        return true;
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return true;
    }

    @Override
    public boolean isPermissionSet(String name) {
        return perms().isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return perms().isPermissionSet(perm);
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

    @Override
    public boolean isConversing() {
        return false;
    }
}

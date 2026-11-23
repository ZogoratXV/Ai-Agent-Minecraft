package dev.tessera.service;

import dev.tessera.Tessera;
import dev.tessera.stub.StubServerBuildInfo;
import io.papermc.paper.ServerBuildInfo;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import net.kyori.adventure.key.Key;

/** Informazioni di build esposte ai plugin tramite {@link ServerBuildInfo#buildInfo()}. */
public final class TesseraBuildInfo extends StubServerBuildInfo {

    public static final Key BRAND = Key.key("tessera", "tessera");

    @Override
    protected String tesseraStubName() {
        return "ServerBuildInfo";
    }

    @Override
    public Key brandId() {
        return BRAND;
    }

    @Override
    public boolean isBrandCompatible(Key brandId) {
        // Molti plugin controllano la compatibilità con Paper: rispondiamo di sì.
        return brandId.equals(BRAND) || brandId.equals(ServerBuildInfo.BRAND_PAPER_ID);
    }

    @Override
    public String brandName() {
        return Tessera.NAME;
    }

    @Override
    public String minecraftVersionId() {
        return Tessera.MINECRAFT_VERSION;
    }

    @Override
    public String minecraftVersionName() {
        return Tessera.MINECRAFT_VERSION;
    }

    @Override
    public OptionalInt buildNumber() {
        return OptionalInt.empty();
    }

    @Override
    public Instant buildTime() {
        return Instant.EPOCH;
    }

    @Override
    public Optional<String> gitBranch() {
        return Optional.empty();
    }

    @Override
    public Optional<String> gitCommit() {
        return Optional.empty();
    }

    @Override
    public String asString(StringRepresentation representation) {
        return Tessera.NAME + " " + Tessera.VERSION + " (MC " + Tessera.MINECRAFT_VERSION + ")";
    }
}

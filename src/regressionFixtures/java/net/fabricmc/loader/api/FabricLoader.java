package net.fabricmc.loader.api;
import java.nio.file.Path;
import java.util.Objects;
public final class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();
    private Path configDir;
    private FabricLoader() {}
    public static FabricLoader getInstance() { return INSTANCE; }
    public void setConfigDir(Path directory) { configDir = Objects.requireNonNull(directory); }
    public Path getConfigDir() {
        return Objects.requireNonNull(configDir, "Set the fixture config directory first");
    }
}

package dev.dizabanik.realisticsmoke;

public final class RealisticSmokeMod {

    public static final FixtureLogger LOGGER = new FixtureLogger();

    private RealisticSmokeMod() {
    }

    public static boolean isSmokeSourceBlock(net.minecraft.block.Block block) {
        return (block instanceof net.minecraft.block.AbstractFurnaceBlock ||
                block instanceof net.minecraft.block.CampfireBlock);
    }

    public static final class FixtureLogger {

        private int warnings;

        public void warn(String message, Object... arguments) {
            warnings++;
        }

        public int warningCount() {
            return warnings;
        }
    }
}

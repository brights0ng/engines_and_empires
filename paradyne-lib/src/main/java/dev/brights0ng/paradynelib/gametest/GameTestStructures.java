package dev.brights0ng.paradynelib.gametest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.google.common.hash.Hashing;

import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

/**
 * Writes the empty structure the game tests build on ({@code <modid>:gametest/empty}): nothing but air, big enough for a
 * thumper on a strike plate with room around it. NeoForge only runs tests whose template is in the mod's own namespace,
 * and no empty one ships with the game, so each mod with game tests adds this provider to its own data generator, with
 * its own id (split step 2b).
 */
public final class GameTestStructures implements DataProvider {

    public static final String EMPTY = "gametest/empty";
    private static final int SIZE = 7;

    private final PackOutput output;
    private final String modId;

    /** Writes {@code <modId>:gametest/empty}. */
    public GameTestStructures(PackOutput output, String modId) {
        this.output = output;
        this.modId = modId;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        return CompletableFuture.runAsync(() -> {
            CompoundTag structure = new CompoundTag();
            structure.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            ListTag size = new ListTag();
            for (int i = 0; i < 3; i++) {
                size.add(IntTag.valueOf(SIZE));
            }
            structure.put("size", size);
            ListTag palette = new ListTag();
            CompoundTag air = new CompoundTag();
            air.put("Name", StringTag.valueOf("minecraft:air"));
            palette.add(air);
            structure.put("palette", palette);
            structure.put("blocks", new ListTag());
            structure.put("entities", new ListTag());
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(structure, bytes);
                byte[] data = bytes.toByteArray();
                Path path = output.getOutputFolder(PackOutput.Target.DATA_PACK)
                        .resolve(modId).resolve("structure").resolve(EMPTY + ".nbt");
                cache.writeIfNeeded(path, data, Hashing.sha1().hashBytes(data));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public String getName() {
        return "Game test structures: " + modId;
    }
}

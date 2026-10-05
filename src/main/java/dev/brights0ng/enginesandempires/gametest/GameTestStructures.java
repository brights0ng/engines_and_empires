package dev.brights0ng.enginesandempires.gametest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.google.common.hash.Hashing;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
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
 * Writes the empty structure the game tests build on ({@code engines_and_empires:gametest/empty}): nothing but air, big
 * enough for a thumper on a strike plate with room around it. NeoForge only runs tests whose template is in the mod's own
 * namespace, and no empty one ships with the game, so the data generator makes one.
 */
public final class GameTestStructures implements DataProvider {

    public static final String EMPTY = "gametest/empty";
    private static final int SIZE = 7;

    private final PackOutput output;

    public GameTestStructures(PackOutput output) {
        this.output = output;
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
                        .resolve(EnginesAndEmpiresMod.MODID).resolve("structure").resolve(EMPTY + ".nbt");
                cache.writeIfNeeded(path, data, Hashing.sha1().hashBytes(data));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public String getName() {
        return "Game test structures";
    }
}

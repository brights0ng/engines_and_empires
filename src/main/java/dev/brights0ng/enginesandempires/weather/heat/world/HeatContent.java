package dev.brights0ng.enginesandempires.weather.heat.world;

import java.util.function.Supplier;

import dev.brights0ng.meteodyne.Meteodyne;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Registers what the heat simulation adds (weather phase 8f-2): its two block data maps and its chunk data. */
public final class HeatContent {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Meteodyne.MODID);

    /** Each chunk's share of saved rooms' warmth ({@link HeatChunkData}). */
    public static final Supplier<AttachmentType<HeatChunkData>> CHUNK_DATA = ATTACHMENTS.register("heat",
            () -> AttachmentType.serializable(HeatChunkData::new).build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
        modEventBus.addListener(HeatTables::register);
    }

    private HeatContent() {
    }
}

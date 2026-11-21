// Written by Claude (GreedyFluids M7d d2).
package dev.brights0ng.meteodyne.airflow;

import java.util.function.Supplier;

import dev.brights0ng.meteodyne.Meteodyne;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Registers what airflow adds (M7d d2): its chunk data, {@code meteodyne:airflow}. */
public final class AirflowContent {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Meteodyne.MODID);

    /** Each chunk's saved airflow temperatures ({@link AirflowChunkData}). */
    public static final Supplier<AttachmentType<AirflowChunkData>> CHUNK_DATA = ATTACHMENTS.register("airflow",
            () -> AttachmentType.serializable(AirflowChunkData::new).build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    private AirflowContent() {
    }
}

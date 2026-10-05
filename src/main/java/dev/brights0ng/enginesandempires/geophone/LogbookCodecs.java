package dev.brights0ng.enginesandempires.geophone;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * How a {@link Logbook} is saved on an item, and sent to players so that a screen can list its readings. One is for saving
 * with the world, the other for the network. An entry saved before entries could be named loads with no name.
 *
 * <p>How many readings it holds is not saved: it is the item's, so each kind of item has its own pair of codecs made by
 * {@link #logbook(int)} and {@link #stream(int)}, and a book always loads back with the room its kind of item has.
 */
public final class LogbookCodecs {

    static final Codec<LogbookEntry> ENTRY = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("number").forGetter(LogbookEntry::number),
            ReaderCodecs.READING.fieldOf("reading").forGetter(LogbookEntry::reading),
            Codec.STRING.optionalFieldOf("name", "").forGetter(LogbookEntry::name)
    ).apply(instance, LogbookEntry::new));

    /** A bare list of entries, for keeping readings somewhere other than an item: the smart logger. */
    public static final Codec<List<LogbookEntry>> ENTRIES = ENTRY.listOf();

    private static final StreamCodec<ByteBuf, LogbookEntry> ENTRY_STREAM = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LogbookEntry::number,
            ReaderCodecs.READING_STREAM, LogbookEntry::reading,
            ByteBufCodecs.stringUtf8(Logbook.MAX_NAME_LENGTH * 4), LogbookEntry::name,
            LogbookEntry::new);

    /** For saving a book with room for {@code capacity} readings. */
    public static Codec<Logbook> logbook(int capacity) {
        return RecordCodecBuilder.create(instance -> instance.group(
                ENTRY.listOf().fieldOf("entries").forGetter(Logbook::entries),
                Codec.INT.fieldOf("next").forGetter(Logbook::nextNumber)
        ).apply(instance, (entries, next) -> new Logbook(entries, next, capacity)));
    }

    /** For sending a book with room for {@code capacity} readings over the network. */
    public static StreamCodec<ByteBuf, Logbook> stream(int capacity) {
        return StreamCodec.composite(
                ENTRY_STREAM.apply(ByteBufCodecs.list(capacity)), Logbook::entries,
                ByteBufCodecs.VAR_INT, Logbook::nextNumber,
                (entries, next) -> new Logbook(entries, next, capacity));
    }

    /** A logbook item's: room for {@link Logbook#MAX_ENTRIES}. */
    public static final Codec<Logbook> LOGBOOK = logbook(Logbook.MAX_ENTRIES);
    public static final StreamCodec<ByteBuf, Logbook> LOGBOOK_STREAM = stream(Logbook.MAX_ENTRIES);

    private LogbookCodecs() {
    }
}

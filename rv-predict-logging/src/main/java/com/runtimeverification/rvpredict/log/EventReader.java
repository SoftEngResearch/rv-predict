package com.runtimeverification.rvpredict.log;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import com.runtimeverification.rvpredict.trace.EventType;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

/**
 * An event input stream lets an application to read {@link Event} from an
 * underlying input stream in a portable way.
 *
 * @author TraianSF
 * @author YilongL
 */
public class EventReader implements Closeable {

    private static final LZ4FastDecompressor FAST_DECOMPRESSOR =
            LZ4Factory.fastestInstance().fastDecompressor();

    private final LZ4BlockInputStream in;

    private final ByteBuffer byteBuffer = ByteBuffer.allocate(Event.SIZEOF);

    private Event lastReadEvent;

    public EventReader(Path path) throws IOException {
        in = new LZ4BlockInputStream(new BufferedInputStream(new FileInputStream(path.toFile()),
                EventWriter.COMPRESS_BLOCK_SIZE), FAST_DECOMPRESSOR);
        readEvent();
    }

    public final Event readEvent() throws IOException {
        int bytes;
        int off = 0;
        int len = Event.SIZEOF;
        while ((bytes = in.read(byteBuffer.array(), off, len)) != len) {
            if (bytes == -1) {
                lastReadEvent = null;
                throw new EOFException();
            }
            off += bytes;
            len -= bytes;
        }
        lastReadEvent = new Event(
                byteBuffer.getLong(),
                byteBuffer.getLong(),
                byteBuffer.getInt(),
                byteBuffer.getInt(),
                byteBuffer.getInt(),
                byteBuffer.getLong(),
                EventType.values()[byteBuffer.get()]);
        byteBuffer.clear();
        return lastReadEvent;
    }

    public Event lastReadEvent() {
        return lastReadEvent;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

}

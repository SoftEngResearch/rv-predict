package com.runtimeverification.rvpredict.log;
import static java.nio.channels.FileChannel.MapMode.READ_WRITE;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.file.Path;

/**
 * An {@link MappedByteBuffer}-backed output stream aimed to support very fast
 * write operations.
 *
 * @author YilongL
 */
public class MappedByteBufferOutputStream extends OutputStream {

    private static final int INIT_CHUNK_SIZE = 32 * 1024 * 1024; // 32MB

    private final RandomAccessFile file;

    private MappedByteBuffer buffer;

    private long fileLen;

    private long filePos;

    private int nextChunkSize = INIT_CHUNK_SIZE;

    public MappedByteBufferOutputStream(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "rw");
        grow();
    }

    @Override
    public void write(int b) throws IOException {
        if (filePos == fileLen) {
            grow();
        }
        filePos++;
        buffer.put((byte) b);
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                   ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }

        if (filePos + len > fileLen) {
            grow();
        }
        filePos += len;
        buffer.put(b, off, len);
    }

    private void grow() throws IOException {
        long remainingBytes = fileLen - filePos;
        fileLen += nextChunkSize;
        file.setLength(fileLen);
        buffer = file.getChannel().map(READ_WRITE, filePos, nextChunkSize + remainingBytes);
        buffer.order(ByteOrder.nativeOrder());
        buffer.position(0);
        nextChunkSize = nextChunkSize << 1;
    }

    @Override
    public void flush() { }

    @Override
    public void close() throws IOException {
        file.setLength(filePos);
        file.close();
    }

}

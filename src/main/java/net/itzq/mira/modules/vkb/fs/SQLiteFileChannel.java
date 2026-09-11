package net.itzq.mira.modules.vkb.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * SQLite 文件通道实现 - 用于读取文件内容
 *
 * @author tangzq
 */
public class SQLiteFileChannel implements SeekableByteChannel {

    private final SQLitePath path;
    private final byte[] content;
    private int position = 0;
    private boolean open = true;

    public SQLiteFileChannel(SQLitePath path, Set<? extends OpenOption> options) throws IOException {
        this.path = path;
        SQLiteFileSystem fs = (SQLiteFileSystem) path.getFileSystem();
        this.content = fs.readFileContent(path.toString());
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        checkOpen();
        if (position >= content.length) {
            return -1;
        }
        int remaining = content.length - position;
        int toRead = Math.min(remaining, dst.remaining());
        dst.put(content, position, toRead);
        position += toRead;
        return toRead;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        throw new NonWritableChannelException();
    }

    @Override
    public long position() throws IOException {
        checkOpen();
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        checkOpen();
        if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid position: " + newPosition);
        }
        this.position = (int) newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        checkOpen();
        return content.length;
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        open = false;
    }

    private void checkOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}

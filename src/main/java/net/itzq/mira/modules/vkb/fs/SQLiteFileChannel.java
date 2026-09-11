package net.itzq.mira.modules.vkb.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Set;

/**
 * SQLite 文件通道实现 - 支持读取和写入
 *
 * @author tangzq
 */
public class SQLiteFileChannel implements SeekableByteChannel {

    private final SQLitePath path;
    private final SQLiteFileSystem fs;
    private final boolean writable;

    // 使用动态 byte 数组管理内存数据
    private byte[] content;
    private int position = 0;
    private boolean open = true;
    private boolean modified = false;

    public SQLiteFileChannel(SQLitePath path, Set<? extends OpenOption> options) throws IOException {
        this.path = path;
        this.fs = (SQLiteFileSystem) path.getFileSystem();
        this.writable = options.contains(StandardOpenOption.WRITE);

        boolean create = options.contains(StandardOpenOption.CREATE);
        boolean createNew = options.contains(StandardOpenOption.CREATE_NEW);
        boolean truncate = options.contains(StandardOpenOption.TRUNCATE_EXISTING);
        boolean append = options.contains(StandardOpenOption.APPEND);

        // 1. 读取现有内容
        byte[] existing = fs.readFileContent(path.toString());

        if (existing == null) {
            // 文件不存在
            if (createNew || create || !writable) {
                // 允许创建，或者只读时系统会抛异常，这里初始化空数组
                existing = new byte[0];
            } else {
                // 明确要求写但不带 CREATE 且文件不存在
                throw new NoSuchFileException(path.toString());
            }
        } else {
            // 文件已存在
            if (createNew) {
                throw new FileAlreadyExistsException(path.toString());
            }
        }

        // 2. 处理截断
        if (truncate && writable) {
            this.content = new byte[0];
            this.modified = true;
        } else {
            this.content = existing;
        }

        // 3. 处理追加模式 (APPEND 会将 position 移动到末尾)
        if (append && writable) {
            this.position = this.content.length;
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        checkOpen();
        if (position >= content.length) {
            return -1; // EOF
        }
        int remaining = content.length - position;
        int toRead = Math.min(remaining, dst.remaining());
        dst.put(content, position, toRead);
        position += toRead;
        return toRead;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        checkOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }

        int toWrite = src.remaining();
        int newPos = position + toWrite;

        // 如果写入超过当前容量，需要扩容
        if (newPos > content.length) {
            content = Arrays.copyOf(content, newPos);
        }

        // 将数据写入 byte 数组
        src.get(content, position, toWrite);
        position = newPos;
        modified = true;

        return toWrite;
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
        checkOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid size: " + size);
        }
        if (size < content.length) {
            content = Arrays.copyOf(content, (int) size);
            if (position > size) {
                position = (int) size;
            }
            modified = true;
        }
        return this;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;

        // 只有在以写模式打开，并且发生过修改时，才回写到数据库
        if (writable && modified) {
            fs.writeFileContent(path.toString(), content);
        }
    }

    private void checkOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}

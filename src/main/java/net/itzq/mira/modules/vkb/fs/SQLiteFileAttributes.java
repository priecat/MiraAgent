package net.itzq.mira.modules.vkb.fs;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

/**
 * SQLite 文件属性实现
 *
 * @author tangzq
 */
public class SQLiteFileAttributes implements BasicFileAttributes {

    private final SQLitePath path;
    private final boolean isDirectory;
    private final long size;
    private final FileTime lastModifiedTime;

    public SQLiteFileAttributes(SQLitePath path) throws IOException {
        this.path = path;
        SQLiteFileSystem fs = (SQLiteFileSystem) path.getFileSystem();
        String pathStr = path.toString();
        this.isDirectory = fs.isDirectory(pathStr);

        if (!isDirectory) {
            this.size = fs.getFileSize(pathStr);
        } else {
            this.size = 0;
        }

        this.lastModifiedTime = FileTime.from(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public FileTime lastModifiedTime() {
        return lastModifiedTime;
    }

    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime;
    }

    @Override
    public FileTime creationTime() {
        return lastModifiedTime;
    }

    @Override
    public boolean isRegularFile() {
        return !isDirectory;
    }

    @Override
    public boolean isDirectory() {
        return isDirectory;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public Object fileKey() {
        return path.toString();
    }
}

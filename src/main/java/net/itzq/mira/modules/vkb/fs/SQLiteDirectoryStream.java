package net.itzq.mira.modules.vkb.fs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQLite 目录流实现
 *
 * @author tangzq
 */
public class SQLiteDirectoryStream implements DirectoryStream<Path> {

    private final SQLitePath dir;
    private final Filter<? super Path> filter;
    private boolean consumed = false;

    public SQLiteDirectoryStream(SQLitePath dir, Filter<? super Path> filter) {
        this.dir = dir;
        this.filter = filter;
    }

    @Override
    public Iterator<Path> iterator() {
        if (consumed) {
            throw new IllegalStateException("Stream already consumed");
        }
        consumed = true;

        try {
            SQLiteFileSystem fs = (SQLiteFileSystem) dir.getFileSystem();
            List<String> entries = fs.listDirectory(dir.toString());
            return entries.stream()
                    .map(name -> (Path) dir.resolve(name))
                    .filter(path -> {
                        try {
                            return filter == null || filter.accept(path);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList())
                    .iterator();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list directory", e);
        }
    }

    @Override
    public void close() throws IOException {
        // nothing to close
    }
}

package io.github.merchantprotocol.trueup;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A table to reconcile: a file, file contents, or rows. {@link #getName()} is how findings refer to its rows
 * ("statement.csv:row 5").
 *
 * <pre>{@code
 * Table.file(Path.of("statement.csv"));
 * Table.content("statement.csv", csvText);
 * Table.rows("statement.csv", List.of(Map.of("Date", "2026-08-03", "Amount", "$99.60")));
 * }</pre>
 */
public final class Table {
    private final String name;
    private final byte[] content;
    private final List<? extends Map<String, ?>> rows;

    private Table(String name, byte[] content, List<? extends Map<String, ?>> rows) {
        this.name = name;
        this.content = content;
        this.rows = rows;
    }

    public static Table file(Path path) {
        try {
            return new Table(path.getFileName().toString(), Files.readAllBytes(path), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Table file(String path) {
        return file(Path.of(path));
    }

    public static Table content(String name, byte[] content) {
        return new Table(name, content, null);
    }

    public static Table content(String name, String content) {
        return new Table(name, content.getBytes(StandardCharsets.UTF_8), null);
    }

    public static Table rows(String name, List<? extends Map<String, ?>> rows) {
        return new Table(name, null, rows);
    }

    public String getName() { return name; }

    boolean isRows() { return rows != null; }

    List<? extends Map<String, ?>> getRows() { return rows; }

    /** File name for a multipart upload. */
    String fileName() {
        if (rows == null) return name;
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + ".json";
    }

    byte[] bytes(Gson gson) {
        return rows == null ? content : gson.toJson(rows).getBytes(StandardCharsets.UTF_8);
    }
}

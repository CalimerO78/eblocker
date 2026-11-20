/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL.
 */
package org.eblocker.server.http.service;

import org.restexpress.exception.BadRequestException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only, bounded view of plain text logs below /var/log. */
public class SystemLogInfoService {
    private static final int MAX_FILES = 200;
    private static final int MAX_WINDOW_BYTES = 1024 * 1024;
    private static final int MAX_LINES = 150;
    private final Path logRoot;

    public SystemLogInfoService() {
        this(Path.of("/var/log"));
    }

    SystemLogInfoService(Path logRoot) {
        this.logRoot = logRoot;
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files());
        result.put("inaccessible", inaccessible());
        return result;
    }

    public Map<String, Object> read(String id, String query) {
        String needle = query == null ? "" : query.trim();
        if (needle.length() > 80 || (!needle.isEmpty() && needle.length() < 3)) {
            throw new BadRequestException("Log search requires 3 to 80 characters");
        }
        Path selected = null;
        for (Map<String, Object> item : files()) {
            if (item.get("id").equals(id)) {
                selected = logRoot.resolve((String) item.get("id"));
                break;
            }
        }
        if (selected == null) throw new BadRequestException("Unknown log file");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("query", needle);
        List<String> lines = new ArrayList<>();
        if ("eblocker/eblocker-history.log".equals(id)) {
            try (BufferedReader reader = Files.newBufferedReader(selected, StandardCharsets.UTF_8)) {
                String line;
                String folded = needle.toLowerCase(Locale.ROOT);
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 2000) line = line.substring(0, 2000) + "…";
                    if (needle.isEmpty() || line.toLowerCase(Locale.ROOT).contains(folded)) {
                        lines.add(line);
                        if (lines.size() > MAX_LINES) lines.remove(0);
                    }
                }
                result.put("windowTruncated", false);
            } catch (IOException e) {
                throw new BadRequestException("Log file cannot be read");
            }
            result.put("lines", lines);
            result.put("maxLines", MAX_LINES);
            return result;
        }
        try (RandomAccessFile file = new RandomAccessFile(selected.toFile(), "r")) {
            long length = file.length();
            long start = Math.max(0, length - MAX_WINDOW_BYTES);
            byte[] bytes = new byte[(int) (length - start)];
            file.seek(start);
            file.readFully(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] candidates = content.split("\\R", -1);
            int first = start > 0 ? 1 : 0;
            String folded = needle.toLowerCase(Locale.ROOT);
            for (int index = first; index < candidates.length; index++) {
                String line = candidates[index];
                if (line.length() > 2000) line = line.substring(0, 2000) + "…";
                if (needle.isEmpty() || line.toLowerCase(Locale.ROOT).contains(folded)) {
                    lines.add(line);
                    if (lines.size() > MAX_LINES) lines.remove(0);
                }
            }
            result.put("windowTruncated", start > 0);
        } catch (IOException e) {
            throw new BadRequestException("Log file cannot be read");
        }
        result.put("lines", lines);
        result.put("maxLines", MAX_LINES);
        return result;
    }

    private List<Map<String, Object>> files() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Files.walkFileTree(logRoot, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 4,
                    new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && Files.isReadable(path) &&
                            isTextLog(path.getFileName().toString())) {
                        try {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", logRoot.relativize(path).toString().replace('\\', '/'));
                            item.put("sizeBytes", Files.size(path));
                            item.put("modifiedAt", Files.getLastModifiedTime(path).toInstant().toString());
                            result.add(item);
                        } catch (IOException ignored) { }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
        result.sort(Comparator.comparing(item -> (String) item.get("id")));
        return result.subList(0, Math.min(result.size(), MAX_FILES));
    }

    private boolean isTextLog(String name) {
        return name.endsWith(".log") || name.matches(".*\\.log\\.[0-9]+");
    }

    private List<String> inaccessible() {
        List<String> result = new ArrayList<>();
        try {
            Files.walkFileTree(logRoot, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 4,
                    new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && isTextLog(path.getFileName().toString()) &&
                            !Files.isReadable(path) && result.size() < 20) {
                        result.add(logRoot.relativize(path).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException error) {
                    if (result.size() < 20) {
                        result.add(logRoot.relativize(path).toString().replace('\\', '/') + "/");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
        result.sort(String::compareTo);
        return result;
    }
}

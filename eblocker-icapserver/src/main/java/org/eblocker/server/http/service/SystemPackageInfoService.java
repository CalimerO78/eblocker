/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL.
 */
package org.eblocker.server.http.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Local package evidence only. No repository refresh or installation is performed. */
final class SystemPackageInfoService {
    private static final List<String> GUEST_TOOLS = List.of(
            "qemu-guest-agent", "open-vm-tools", "hyperv-daemons",
            "spice-vdagent", "virtualbox-guest-utils");
    private static final List<String> FIRMWARE_PACKAGES = List.of(
            "firmware-linux-free", "firmware-linux-nonfree", "intel-microcode", "amd64-microcode");
    private final Path debianCache;
    private final Path dpkgStatus;
    private final Path aptDirectory;
    private final Path aptLists;
    private Map<String, Object> cached;
    private long cachedAt;

    SystemPackageInfoService() {
        this(Path.of("/var/lib/dpkg/status"), Path.of("/etc/apt"), Path.of("/var/lib/apt/lists"),
                Path.of("/var/cache/eblocker/systeminfo-debian"));
    }

    SystemPackageInfoService(Path dpkgStatus, Path aptDirectory, Path aptLists) {
        this(dpkgStatus, aptDirectory, aptLists, Path.of("/var/cache/eblocker/systeminfo-debian"));
    }

    SystemPackageInfoService(Path dpkgStatus, Path aptDirectory, Path aptLists, Path debianCache) {
        this.dpkgStatus = dpkgStatus;
        this.aptDirectory = aptDirectory;
        this.aptLists = aptLists;
        this.debianCache = debianCache;
    }

    synchronized Map<String, Object> snapshot() {
        long now = System.nanoTime();
        if (cached == null || now - cachedAt > TimeUnit.MINUTES.toNanos(5)) {
            cached = collect();
            cachedAt = now;
        }
        return cached;
    }

    private Map<String, Object> collect() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> installed = installedPackages();
        List<Map<String, String>> updates = availableUpdates();
        Map<String, String> candidates = new LinkedHashMap<>();
        if (updates != null) {
            for (Map<String, String> update : updates) {
                candidates.put(update.get("name"), update.get("version"));
            }
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : GUEST_TOOLS) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("version", installed.get(name));
            tool.put("availableVersion", candidates.get(name));
            tools.add(tool);
        }
        result.put("guestTools", tools);
        Map<String, String> debianCandidates = debianCandidates();
        result.put("debianGuestTools", versionRows(GUEST_TOOLS, installed, debianCandidates));
        result.put("debianFirmware", versionRows(FIRMWARE_PACKAGES, installed, debianCandidates));
        result.put("debianListsAt", readDebianListsAt());
        result.put("sources", sources());
        result.put("packageListsAt", packageListsAt());
        result.put("updateCount", updates == null ? null : updates.size());
        result.put("updates", updates == null ? List.of() : updates);
        List<Map<String, String>> firmware = new ArrayList<>();
        if (updates != null) {
            for (Map<String, String> update : updates) {
                String name = update.get("name");
                if (name.contains("firmware") || name.contains("microcode")) firmware.add(update);
            }
        }
        result.put("firmwareUpdateCount", updates == null ? null : firmware.size());
        result.put("firmwareUpdates", firmware.subList(0, Math.min(firmware.size(), 8)));
        return result;
    }

    private List<Map<String, String>> versionRows(List<String> names, Map<String, String> installed,
                                                   Map<String, String> candidates) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String name : names) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("installedVersion", installed.get(name));
            row.put("availableVersion", candidates.get(name));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, String> debianCandidates() {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(debianCache.resolve("candidates.tsv"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length == 2 &&
                        (GUEST_TOOLS.contains(fields[0]) || FIRMWARE_PACKAGES.contains(fields[0])) &&
                        !fields[1].isEmpty()) {
                    result.put(fields[0], fields[1]);
                }
            }
        } catch (IOException ignored) { }
        return result;
    }

    private String readDebianListsAt() {
        try {
            return Files.readString(debianCache.resolve("refreshed-at.txt")).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private Map<String, String> installedPackages() {
        Map<String, String> installed = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(dpkgStatus)) {
            String line;
            String name = null;
            String version = null;
            boolean active = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (active && name != null) installed.put(name, version);
                    name = null;
                    version = null;
                    active = false;
                } else if (line.startsWith("Package: ")) name = line.substring(9).trim();
                else if (line.startsWith("Version: ")) version = line.substring(9).trim();
                else if (line.equals("Status: install ok installed")) active = true;
            }
            if (active && name != null) installed.put(name, version);
        } catch (IOException ignored) { }
        return installed;
    }

    private List<Map<String, String>> sources() {
        List<Map<String, String>> result = new ArrayList<>();
        parseSourceFile(aptDirectory.resolve("sources.list"), result);
        Path fragments = aptDirectory.resolve("sources.list.d");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(fragments)) {
            for (Path file : files) {
                if (file.toString().endsWith(".list") || file.toString().endsWith(".sources")) {
                    parseSourceFile(file, result);
                }
            }
        } catch (IOException ignored) { }
        return result.subList(0, Math.min(result.size(), 20));
    }

    private void parseSourceFile(Path file, List<Map<String, String>> result) {
        String uri = null;
        String suites = null;
        boolean disabled = false;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null && result.size() < 20) {
                String value = line.trim();
                if (value.startsWith("deb ") || value.startsWith("deb-src ")) {
                    String[] parts = value.replaceFirst("^(deb|deb-src)\\s+(\\[[^]]+\\]\\s+)?", "").split("\\s+");
                    if (parts.length >= 2) addSource(result, parts[0], parts[1], file);
                } else if (value.startsWith("URIs: ")) uri = value.substring(6).trim().split("\\s+")[0];
                else if (value.startsWith("Suites: ")) suites = value.substring(8).trim();
                else if (value.equalsIgnoreCase("Enabled: no")) disabled = true;
                else if (value.isEmpty() && uri != null) {
                    if (!disabled && suites != null) addSource(result, uri, suites, file);
                    uri = null;
                    suites = null;
                    disabled = false;
                }
            }
            if (!disabled && uri != null && suites != null) addSource(result, uri, suites, file);
        } catch (IOException ignored) { }
    }

    private void addSource(List<Map<String, String>> result, String address, String suites, Path file) {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return;
            Map<String, String> source = new LinkedHashMap<>();
            source.put("host", host);
            source.put("file", file.getFileName().toString());
            source.put("suite", suites.length() > 100 ? suites.substring(0, 100) : suites);
            if (!result.contains(source)) result.add(source);
        } catch (IllegalArgumentException ignored) { }
    }

    private String packageListsAt() {
        Instant newest = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(aptLists, "*Packages*")) {
            for (Path file : files) {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (newest == null || modified.isAfter(newest)) newest = modified;
            }
        } catch (IOException ignored) { }
        return newest == null ? null : newest.toString();
    }

    private List<Map<String, String>> availableUpdates() {
        if (!Files.isDirectory(aptLists)) return null;
        Process process = null;
        try {
            process = new ProcessBuilder("apt", "list", "--upgradable")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            List<Map<String, String>> updates = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && updates.size() < 500) {
                    Map<String, String> update = parseUpdate(line);
                    if (update != null) updates.add(update);
                }
            }
            return updates;
        } catch (InterruptedException ignored) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException ignored) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    static Map<String, String> parseUpdate(String line) {
        int slash = line.indexOf('/');
        int space = line.indexOf(' ', slash + 1);
        int from = line.indexOf("[upgradable from: ");
        if (slash < 1 || space < 0 || from < 0) return null;
        String name = line.substring(0, slash);
        String[] fields = line.substring(space + 1, from).trim().split("\\s+");
        if (fields.length < 1) return null;
        String candidate = fields[0];
        String installed = line.substring(from + 18, line.indexOf(']', from));
        if (candidate.equals(installed)) return null;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("version", candidate);
        result.put("installedVersion", installed);
        return result;
    }
}

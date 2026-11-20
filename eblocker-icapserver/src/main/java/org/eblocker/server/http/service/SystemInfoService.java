/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL
 * (the "License"); You may not use this work except in compliance with
 * the License. You may obtain a copy of the License at:
 *
 *   https://joinup.ec.europa.eu/page/eupl-text-11-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.eblocker.server.http.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.eblocker.server.common.data.DataSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Read-only, bounded system snapshot for the authenticated admin console. */
@Singleton
public class SystemInfoService {
    private final String version;
    private final Path proc;
    private final Path etc;
    private final Path sys;
    private final Clock clock;
    private final DataSource dataSource;
    private SystemPackageInfoService packageInfo;

    @Inject
    public SystemInfoService(@Named("project.version") String version, DataSource dataSource) {
        this(version, Path.of("/proc"), Path.of("/etc"), Path.of("/sys"), Clock.systemUTC(), dataSource);
        packageInfo = new SystemPackageInfoService();
    }

    SystemInfoService(String version, Path proc, Path etc, Path sys, Clock clock, DataSource dataSource) {
        this.version = version;
        this.proc = proc;
        this.etc = etc;
        this.sys = sys;
        this.clock = clock;
        this.dataSource = dataSource;
        this.packageInfo = null;
    }

    public Map<String, Object> getSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampledAt", Instant.now(clock).toString());
        result.put("version", version);
        result.put("build", build());
        result.put("hardware", hardware());
        result.put("cpu", cpu());
        result.put("memory", memory());
        result.put("uptimeSeconds", uptime());
        result.put("storage", storage());
        result.put("network", network());
        result.put("networkMode", networkMode());
        result.put("dnsServers", dnsServers());
        result.put("time", time());
        result.put("temperatures", temperatures());
        result.put("packages", packageInfo == null ? null : packageInfo.snapshot());
        return result;
    }

    private Map<String, Object> build() {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/systeminfo-build.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException ignored) {
            // Missing build metadata must never hide the rest of the snapshot.
        }
        Path installedManifest = etc.resolve("eblocker/systeminfo-build.properties");
        if (Files.isRegularFile(installedManifest)) {
            try (InputStream input = Files.newInputStream(installedManifest)) {
                properties.load(input);
            } catch (IOException ignored) {
                // Keep the embedded provenance if the optional local manifest is unreadable.
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseRevision", properties.getProperty("baseRevision"));
        result.put("enhancements", properties.getProperty("enhancements"));
        result.put("buildId", properties.getProperty("buildId"));
        return result;
    }

    private Map<String, Object> hardware() {
        String architecture = System.getProperty("os.arch");
        String cpuModel = null;
        String model = null;
        boolean hypervisor = false;
        for (String line : readLines(proc.resolve("cpuinfo"), 256)) {
            if (cpuModel == null && (line.startsWith("model name") || line.startsWith("Hardware"))) {
                cpuModel = valueAfterColon(line);
            }
            if (line.startsWith("Model")) model = valueAfterColon(line);
            if (line.startsWith("flags") && line.contains(" hypervisor")) hypervisor = true;
        }
        String product = readTrimmed(sys.resolve("class/dmi/id/product_name"));
        String platform;
        if ((model != null && model.toLowerCase().contains("raspberry pi")) ||
                (product != null && product.toLowerCase().contains("raspberry pi"))) {
            platform = "RASPBERRY_PI";
        } else if (hypervisor || isVirtualProduct(product)) {
            platform = "VM";
        } else if (architecture != null && (architecture.contains("arm") || architecture.contains("aarch64"))) {
            platform = "ARM";
        } else if (architecture != null && (architecture.contains("86") || architecture.contains("amd64"))) {
            platform = "X86";
        } else {
            platform = "UNKNOWN";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform);
        result.put("architecture", architecture);
        result.put("model", model != null ? model : product);
        result.put("cpuModel", cpuModel);
        return result;
    }

    private boolean isVirtualProduct(String product) {
        if (product == null) return false;
        String lower = product.toLowerCase();
        return lower.contains("virtual") || lower.contains("kvm") || lower.contains("qemu") ||
                lower.contains("vmware") || lower.contains("proxmox") || lower.contains("hyper-v");
    }

    private Map<String, Object> cpu() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logicalCores", Runtime.getRuntime().availableProcessors());
        Double load = null;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            double value = ((com.sun.management.OperatingSystemMXBean) bean).getSystemCpuLoad();
            if (value >= 0 && value <= 1) load = Math.round(value * 1000.0) / 10.0;
        }
        result.put("loadPercent", load);
        return result;
    }

    private Map<String, Object> memory() {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : readLines(proc.resolve("meminfo"), 128)) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String[] fields = line.substring(colon + 1).trim().split("\\s+");
            if (fields.length == 0) continue;
            try { values.put(line.substring(0, colon), Long.parseLong(fields[0]) * 1024); }
            catch (NumberFormatException ignored) { }
        }
        Long total = values.get("MemTotal");
        Long available = values.get("MemAvailable");
        Long free = values.get("MemFree");
        Long swapTotal = values.get("SwapTotal");
        Long swapFree = values.get("SwapFree");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalBytes", total);
        result.put("availableBytes", available);
        result.put("freeBytes", free);
        result.put("usedBytes", total != null && available != null ? Math.max(0, total - available) : null);
        result.put("swapTotalBytes", swapTotal);
        result.put("swapUsedBytes", swapTotal != null && swapFree != null ? Math.max(0, swapTotal - swapFree) : null);
        Runtime runtime = Runtime.getRuntime();
        result.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        result.put("heapMaxBytes", runtime.maxMemory());
        return result;
    }

    private Long uptime() {
        String line = readTrimmed(proc.resolve("uptime"));
        if (line == null) return null;
        try { return (long) Double.parseDouble(line.split("\\s+")[0]); }
        catch (NumberFormatException ignored) { return null; }
    }

    private List<Map<String, Object>> storage() {
        List<Map<String, Object>> result = new ArrayList<>();
        addStorage(result, Path.of("/"), "system");
        Path data = Path.of("/var/lib/eblocker");
        if (Files.isDirectory(data)) {
            try {
                FileStore rootStore = Files.getFileStore(Path.of("/"));
                FileStore dataStore = Files.getFileStore(data);
                if (!rootStore.name().equals(dataStore.name()) || !rootStore.type().equals(dataStore.type())) {
                    addStorage(result, data, "data");
                }
            } catch (IOException ignored) { }
        }
        return result;
    }

    private void addStorage(List<Map<String, Object>> result, Path path, String role) {
        try {
            FileStore fileStore = Files.getFileStore(path);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("mount", path.toString());
            item.put("totalBytes", fileStore.getTotalSpace());
            item.put("availableBytes", fileStore.getUsableSpace());
            result.add(item);
        } catch (IOException ignored) { }
    }

    private List<Map<String, Object>> network() {
        Map<String, List<String>> gateways = gateways();
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            for (NetworkInterface adapter : Collections.list(interfaces)) {
                if (adapter.isLoopback()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", adapter.getName());
                item.put("description", adapter.getDisplayName());
                item.put("up", adapter.isUp());
                List<String> addresses = new ArrayList<>();
                for (InterfaceAddress address : adapter.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip != null) addresses.add(ip.getHostAddress().replaceFirst("%.*$", "") + "/" + address.getNetworkPrefixLength());
                }
                item.put("addresses", addresses);
                item.put("gateways", gateways.getOrDefault(adapter.getName(), List.of()));
                Path net = sys.resolve("class/net").resolve(adapter.getName());
                item.put("speedMbps", positiveLong(readTrimmed(net.resolve("speed"))));
                item.put("rxBytes", positiveLong(readTrimmed(net.resolve("statistics/rx_bytes"))));
                item.put("txBytes", positiveLong(readTrimmed(net.resolve("statistics/tx_bytes"))));
                result.add(item);
            }
        } catch (SocketException ignored) { }
        result.sort(Comparator.comparing(item -> (String) item.get("name")));
        return result;
    }

    Map<String, List<String>> gateways() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String line : readLines(proc.resolve("net/route"), 256)) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 4 || !"00000000".equals(fields[1])) continue;
            try {
                long gateway = Long.parseLong(fields[2], 16);
                if (gateway == 0) continue;
                String ip = (gateway & 255) + "." + ((gateway >> 8) & 255) + "." +
                        ((gateway >> 16) & 255) + "." + ((gateway >> 24) & 255);
                result.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(ip);
            } catch (NumberFormatException ignored) { }
        }
        for (String line : readLines(proc.resolve("net/ipv6_route"), 256)) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 10 || !fields[0].matches("0{32}") || !"00000000".equals(fields[1]) ||
                    fields[4].matches("0{32}")) continue;
            try {
                byte[] bytes = new byte[16];
                for (int index = 0; index < 16; index++) bytes[index] = (byte) Integer.parseInt(fields[4].substring(index * 2, index * 2 + 2), 16);
                result.computeIfAbsent(fields[9], ignored -> new ArrayList<>()).add(InetAddress.getByAddress(bytes).getHostAddress());
            } catch (IOException | NumberFormatException ignored) { }
        }
        return result;
    }

    private List<String> dnsServers() {
        List<String> result = new ArrayList<>();
        for (String line : readLines(etc.resolve("resolv.conf"), 64)) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length == 2 && "nameserver".equals(fields[0])) result.add(fields[1]);
        }
        return result;
    }

    private String networkMode() {
        if (dataSource == null) return null;
        try {
            return dataSource.getCurrentNetworkState().name();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> time() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zone", ZoneId.systemDefault().toString());
        result.put("synchronized", Files.exists(Path.of("/run/systemd/timesync/synchronized")) ? true : null);
        return result;
    }

    private List<Map<String, Object>> temperatures() {
        List<Map<String, Object>> result = new ArrayList<>();
        Path thermal = sys.resolve("class/thermal");
        try (DirectoryStream<Path> zones = Files.newDirectoryStream(thermal, "thermal_zone*")) {
            for (Path zone : zones) {
                if (result.size() >= 16) break;
                String type = readTrimmed(zone.resolve("type"));
                Long milliCelsius = positiveLong(readTrimmed(zone.resolve("temp")));
                if (type == null || milliCelsius == null || milliCelsius > 150000) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", type);
                item.put("celsius", milliCelsius / 1000.0);
                result.add(item);
            }
        } catch (IOException ignored) { }
        return result;
    }

    private Long positiveLong(String value) {
        if (value == null) return null;
        try { long parsed = Long.parseLong(value); return parsed >= 0 ? parsed : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private String valueAfterColon(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? null : line.substring(colon + 1).trim();
    }

    private String readTrimmed(Path path) {
        List<String> lines = readLines(path, 1);
        return lines.isEmpty() ? null : lines.get(0).trim();
    }

    private List<String> readLines(Path path, int limit) {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while (result.size() < limit && (line = reader.readLine()) != null) result.add(line);
        } catch (IOException | RuntimeException ignored) { }
        return result;
    }
}

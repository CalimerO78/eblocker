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

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

public class SystemInfoServiceTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @SuppressWarnings("unchecked")
    @Test
    public void readsBoundedLinuxSnapshotWithoutSecrets() throws Exception {
        Path root = folder.newFolder().toPath();
        Path proc = Files.createDirectories(root.resolve("proc"));
        Path etc = Files.createDirectories(root.resolve("etc"));
        Path sys = Files.createDirectories(root.resolve("sys/class/dmi/id"));
        Files.writeString(proc.resolve("cpuinfo"), "model name : Example CPU\nflags : fpu hypervisor\n");
        Files.writeString(proc.resolve("meminfo"), "MemTotal: 4096000 kB\nMemAvailable: 1024000 kB\nMemFree: 500000 kB\nSwapTotal: 512000 kB\nSwapFree: 256000 kB\n");
        Files.writeString(proc.resolve("uptime"), "90061.24 0.00\n");
        Files.writeString(sys.resolve("product_name"), "KVM\n");
        Files.writeString(etc.resolve("resolv.conf"), "nameserver 192.0.2.53\nsearch example.test\n");
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);

        Map<String, Object> snapshot = new SystemInfoService("4.1.0", proc, etc, root.resolve("sys"), clock, null).getSnapshot();
        Map<String, Object> hardware = (Map<String, Object>) snapshot.get("hardware");
        Map<String, Object> memory = (Map<String, Object>) snapshot.get("memory");
        Map<String, Object> build = (Map<String, Object>) snapshot.get("build");
        Assert.assertEquals("2026-11-15T10:00:00Z", snapshot.get("sampledAt"));
        Assert.assertEquals("4.1.0", snapshot.get("version"));
        Assert.assertEquals("VM", hardware.get("platform"));
        Assert.assertEquals("Example CPU", hardware.get("cpuModel"));
        Assert.assertEquals(90061L, snapshot.get("uptimeSeconds"));
        Assert.assertEquals(4096000L * 1024, memory.get("totalBytes"));
        Assert.assertEquals(1024000L * 1024, memory.get("availableBytes"));
        Assert.assertEquals(500000L * 1024, memory.get("freeBytes"));
        Assert.assertEquals(256000L * 1024, memory.get("swapUsedBytes"));
        Assert.assertEquals(List.of("192.0.2.53"), snapshot.get("dnsServers"));
        Assert.assertEquals("content-picker-scriptlets2,systeminfo-41,wireguard-full", build.get("enhancements"));
        Assert.assertFalse(snapshot.toString().contains("search example.test"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void missingOptionalSourcesAreReportedAsUnavailable() throws Exception {
        Path root = folder.newFolder().toPath();
        Map<String, Object> snapshot = new SystemInfoService("4.1.0", root, root, root, Clock.systemUTC(), null).getSnapshot();
        Map<String, Object> memory = (Map<String, Object>) snapshot.get("memory");
        Assert.assertNull(memory.get("availableBytes"));
        Assert.assertNull(snapshot.get("uptimeSeconds"));
        Assert.assertEquals(List.of(), snapshot.get("dnsServers"));
        Assert.assertEquals(List.of(), snapshot.get("temperatures"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void embeddedBuildProvenanceOverridesStaleLocalManifest() throws Exception {
        Path root = folder.newFolder().toPath();
        Path etc = Files.createDirectories(root.resolve("etc/eblocker"));
        Files.writeString(etc.resolve("systeminfo-build.properties"),
                "baseRevision=old-revision\n" +
                "enhancements=old-feature\n" +
                "buildId=old-build\n");

        Map<String, Object> snapshot = new SystemInfoService("4.1.0", root, root.resolve("etc"), root,
                Clock.systemUTC(), null).getSnapshot();
        Map<String, Object> build = (Map<String, Object>) snapshot.get("build");
        Assert.assertEquals("6dd560a666d10f804b943f56192cfa306b60d7c2", build.get("baseRevision"));
        Assert.assertNotEquals("old-build", build.get("buildId"));
    }

    @Test
    public void decodesIpv4AndIpv6DefaultGateways() throws Exception {
        Path root = folder.newFolder().toPath();
        Path proc = Files.createDirectories(root.resolve("proc/net"));
        Files.writeString(proc.resolve("route"),
                "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT\n" +
                "eth0 00000000 0102A8C0 0003 0 0 100 00000000 0 0 0\n");
        Files.writeString(proc.resolve("ipv6_route"),
                "00000000000000000000000000000000 00000000 " +
                "00000000000000000000000000000000 00000000 " +
                "20010db8000000000000000000000001 00000064 00000000 00000000 00000003 eth0\n");
        SystemInfoService service = new SystemInfoService("4.1.0", root.resolve("proc"), root, root,
                Clock.systemUTC(), null);
        Assert.assertEquals(List.of("192.168.2.1", "2001:db8:0:0:0:0:0:1"), service.gateways().get("eth0"));
    }
}

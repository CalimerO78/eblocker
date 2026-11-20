package org.eblocker.server.http.service;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SystemPackageInfoServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @SuppressWarnings("unchecked")
    @Test
    public void readsInstalledToolsAndSanitizesSourceCredentials() throws Exception {
        Path root = folder.newFolder().toPath();
        Path status = root.resolve("status");
        Files.writeString(status, "Package: qemu-guest-agent\nStatus: install ok installed\nVersion: 1:7.2\n\n" +
                "Package: open-vm-tools\nStatus: deinstall ok config-files\nVersion: 1.0\n\n");
        Path sources = Files.createDirectories(root.resolve("apt/sources.list.d"));
        Files.writeString(sources.resolve("private.list"),
                "deb https://user:secret@repo.example.test/debian bookworm main\n");
        Map<String, Object> snapshot = new SystemPackageInfoService(status, root.resolve("apt"),
                root.resolve("missing-lists")).snapshot();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) snapshot.get("guestTools");
        Assert.assertEquals("1:7.2", tools.get(0).get("version"));
        Assert.assertNull(tools.get(1).get("version"));
        Assert.assertEquals("repo.example.test", ((List<Map<String, String>>) snapshot.get("sources")).get(0).get("host"));
        Assert.assertFalse(snapshot.toString().contains("secret"));
        Assert.assertNull(snapshot.get("updateCount"));
    }

    @Test
    public void excludesSameVersionFromCandidateUpdates() {
        Assert.assertNull(SystemPackageInfoService.parseUpdate(
                "eblocker-ui/bookworm 4.1.0 all [upgradable from: 4.1.0]"));
        Map<String, String> update = SystemPackageInfoService.parseUpdate(
                "intel-microcode/bookworm 3.2026 amd64 [upgradable from: 3.2025]");
        Assert.assertEquals("intel-microcode", update.get("name"));
        Assert.assertEquals("3.2026", update.get("version"));
        Assert.assertEquals("3.2025", update.get("installedVersion"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void showsIsolatedDebianCandidatesWithoutUsingSystemSources() throws Exception {
        Path root = folder.newFolder().toPath();
        Path status = root.resolve("status");
        Files.writeString(status, "Package: qemu-guest-agent\nStatus: install ok installed\n" +
                "Version: 1:7.2-old\n\n");
        Path cache = Files.createDirectories(root.resolve("debian-cache"));
        Files.writeString(cache.resolve("candidates.tsv"),
                "qemu-guest-agent\t1:7.2-new\nintel-microcode\t3.2025\n" +
                "unrelated\tsecret\n");
        Files.writeString(cache.resolve("refreshed-at.txt"), "2026-11-15T13:00:00Z\n");
        Map<String, Object> snapshot = new SystemPackageInfoService(status, root.resolve("apt"),
                root.resolve("lists"), cache).snapshot();
        List<Map<String, String>> tools = (List<Map<String, String>>) snapshot.get("debianGuestTools");
        Assert.assertEquals("1:7.2-old", tools.get(0).get("installedVersion"));
        Assert.assertEquals("1:7.2-new", tools.get(0).get("availableVersion"));
        Assert.assertEquals("2026-11-15T13:00:00Z", snapshot.get("debianListsAt"));
        Assert.assertFalse(snapshot.toString().contains("secret"));
    }
}

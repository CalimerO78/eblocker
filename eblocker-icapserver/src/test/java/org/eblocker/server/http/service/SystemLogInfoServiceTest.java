package org.eblocker.server.http.service;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.restexpress.exception.BadRequestException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SystemLogInfoServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @SuppressWarnings("unchecked")
    @Test
    public void listsOnlyTextLogsAndSearchesSelectedFile() throws Exception {
        Path root = folder.newFolder().toPath();
        Path sub = Files.createDirectories(root.resolve("eblocker"));
        Files.writeString(sub.resolve("system.log"), "booted\nerror one\nerror two\n");
        Files.writeString(sub.resolve("system.log.xz"), "archive");
        Files.writeString(root.resolve("journal"), "binary");
        Files.createSymbolicLink(sub.resolve("outside.log"), root.resolve("journal"));
        SystemLogInfoService service = new SystemLogInfoService(root);

        List<Map<String, Object>> files = (List<Map<String, Object>>) service.overview().get("files");
        Assert.assertEquals(1, files.size());
        Assert.assertEquals("eblocker/system.log", files.get(0).get("id"));
        Map<String, Object> found = service.read("eblocker/system.log", "error");
        Assert.assertEquals(List.of("error one", "error two"), found.get("lines"));
        Assert.assertEquals(List.of("booted", "error one", "error two", ""),
                service.read("eblocker/system.log", "").get("lines"));
    }

    @Test
    public void historicalLogSearchReadsPastLastMegabyte() throws Exception {
        Path root = folder.newFolder().toPath();
        Path sub = Files.createDirectories(root.resolve("eblocker"));
        StringBuilder log = new StringBuilder("early historical match\n");
        for (int i = 0; i < 12000; i++) log.append("ordinary line ").append(i).append(" with padding to fill the file...............................................\n");
        Files.writeString(sub.resolve("eblocker-history.log"), log.toString());
        SystemLogInfoService service = new SystemLogInfoService(root);
        Assert.assertEquals(List.of("early historical match"),
                service.read("eblocker/eblocker-history.log", "historical").get("lines"));
    }

    @Test(expected = BadRequestException.class)
    public void rejectsPathOutsideInventoriedLogs() throws Exception {
        Path root = folder.newFolder().toPath();
        new SystemLogInfoService(root).read("../secrets.log", "");
    }

    @Test(expected = BadRequestException.class)
    public void rejectsShortSearch() throws Exception {
        Path root = folder.newFolder().toPath();
        Files.writeString(root.resolve("system.log"), "hello\n");
        new SystemLogInfoService(root).read("system.log", "he");
    }
}

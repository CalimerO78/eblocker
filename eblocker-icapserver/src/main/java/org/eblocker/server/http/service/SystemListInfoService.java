/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2 or subsequent versions.
 */
package org.eblocker.server.http.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.eblocker.server.common.blacklist.DomainBlacklistService;
import org.eblocker.server.common.blacklist.DomainFilter;
import org.eblocker.server.common.data.parentalcontrol.ParentalControlFilterMetaData;
import org.eblocker.server.common.data.statistic.FilterStatisticsDataSource;
import org.eblocker.server.common.data.statistic.TotalCounter;
import org.restexpress.exception.BadRequestException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Read-only catalogue and bounded substring search of locally installed filter lists. */
@Singleton
public class SystemListInfoService {
    private static final int RESULTS_PER_LIST = 8;
    private static final Path HISTORICAL_BLOCKS = Path.of("/var/lib/eblocker/systeminfo/history-blocks.tsv");
    private final ParentalControlFilterListsService filterLists;
    private final DomainBlacklistService blacklist;
    private final FilterStatisticsDataSource statistics;
    private final ObjectMapper mapper;
    private final Path easylistDirectory;
    private final Path malwareUrls;
    private final Path malwareIps;
    private final Path malwareIp6s;

    @Inject
    public SystemListInfoService(ParentalControlFilterListsService filterLists,
                                 DomainBlacklistService blacklist,
                                 FilterStatisticsDataSource statistics,
                                 ObjectMapper mapper,
                                 @Named("contentFilter.file.path") String contentFilterPath,
                                 @Named("malware.filter.urls.file.path") String malwareUrls,
                                 @Named("malware.filter.ips.file.path") String malwareIps,
                                 @Named("malware.filter.ip6s.file.path") String malwareIp6s) {
        this(filterLists, blacklist, statistics, mapper, Path.of(contentFilterPath).getParent(),
                Path.of(malwareUrls), Path.of(malwareIps), Path.of(malwareIp6s));
    }

    SystemListInfoService(ParentalControlFilterListsService filterLists,
                          DomainBlacklistService blacklist,
                          FilterStatisticsDataSource statistics,
                          ObjectMapper mapper, Path easylistDirectory, Path malwareUrls,
                          Path malwareIps, Path malwareIp6s) {
        this.filterLists = filterLists;
        this.blacklist = blacklist;
        this.statistics = statistics;
        this.mapper = mapper;
        this.easylistDirectory = easylistDirectory;
        this.malwareUrls = malwareUrls;
        this.malwareIps = malwareIps;
        this.malwareIp6s = malwareIp6s;
    }

    public Map<String, Object> overview() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sampledAt", Instant.now().toString());
        List<Map<String, Object>> lists = new ArrayList<>();
        Map<Integer, Long> blocksByList = new LinkedHashMap<>();
        Long totalBlocked = null;
        Instant statsSince = null;
        try {
            totalBlocked = 0L;
            statsSince = statistics.getLastResetTotalCounters();
            for (TotalCounter counter : statistics.getTotalCounters(null)) {
                if (!"blocked_queries".equals(counter.getName())) continue;
                totalBlocked += counter.getValue();
                try {
                    int id = Integer.parseInt(counter.getReason());
                    blocksByList.merge(id, (long) counter.getValue(), Long::sum);
                } catch (NumberFormatException | NullPointerException ignored) { }
            }
        } catch (RuntimeException ignored) {
            totalBlocked = null;
        }
        Map<Integer, Long> historicalByList = new LinkedHashMap<>();
        Long historicalTotal = readHistoricalBlocks(historicalByList);
        if (historicalTotal != null && totalBlocked != null) {
            totalBlocked += historicalTotal;
            historicalByList.forEach((id, count) -> blocksByList.merge(id, count, Long::sum));
        }
        response.put("blockedRequests", totalBlocked);
        response.put("historicalImported", historicalTotal != null && totalBlocked != null);
        response.put("statsSince", statsSince == null ? null : statsSince.toString());

        for (ParentalControlFilterMetaData metadata : filterLists.getParentalControlFilterMetaData()) {
            Integer size = metadata.getSize();
            if (size == null && metadata.getId() != null) {
                DomainFilter<?> active = blacklist.getFilter(metadata.getId());
                if (active != null) size = active.getSize();
            }
            Map<String, Object> item = item("domain:" + metadata.getId(),
                    metadata.getCategory() == null ? "OTHER" : metadata.getCategory().name(),
                    metadata.getCustomerCreatedName(), metadata.getName(), size,
                    metadata.getDate() == null ? null : metadata.getDate().toInstant(),
                    metadata.getVersion(), !metadata.isDisabled(),
                    "domainblacklist/string".equals(metadata.getFormat()));
            item.put("blockedRequests", totalBlocked == null ? null : blocksByList.getOrDefault(metadata.getId(), 0L));
            lists.add(item);
        }
        for (Path path : easylistFiles()) {
            lists.add(item("rules:" + path.getFileName(), "CONTENT_RULES", path.getFileName().toString(),
                    null, countRules(path), modified(path), null, true, true));
        }
        addFeed(lists, "urls", malwareUrls, true);
        addFeed(lists, "ip4", malwareIps, false);
        addFeed(lists, "ip6", malwareIp6s, false);
        lists.sort(Comparator.comparing((Map<String, Object> item) -> (String) item.get("category"))
                .thenComparing(item -> (String) item.get("id")));
        response.put("lists", lists);
        response.put("listCount", lists.size());
        return response;
    }

    private Long readHistoricalBlocks(Map<Integer, Long> byList) {
        if (!Files.isRegularFile(HISTORICAL_BLOCKS)) return null;
        Long total = null;
        try (BufferedReader reader = Files.newBufferedReader(HISTORICAL_BLOCKS, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 2) continue;
                long value = Long.parseLong(fields[1]);
                if (value < 0) continue;
                if ("total".equals(fields[0])) total = value;
                else byList.put(Integer.parseInt(fields[0]), value);
            }
        } catch (IOException | NumberFormatException ignored) {
            byList.clear();
            return null;
        }
        if (total == null) byList.clear();
        return total;
    }

    public Map<String, Object> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.length() < 3 || query.length() > 80) {
            throw new BadRequestException("Search term must contain 3 to 80 characters");
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        int unavailable = 0;
        for (ParentalControlFilterMetaData metadata : filterLists.getParentalControlFilterMetaData()) {
            if (!"domainblacklist/string".equals(metadata.getFormat())) {
                unavailable++;
                continue;
            }
            DomainFilter<?> filter = blacklist.getFilter(metadata.getId());
            if (filter == null) {
                unavailable++;
                continue;
            }
            try (Stream<?> domains = filter.getDomains()) {
                List<String> entries = domains.map(Object::toString)
                        .filter(domain -> domain.toLowerCase(Locale.ROOT).contains(query))
                        .limit(RESULTS_PER_LIST).collect(Collectors.toList());
                addMatch(matches, "domain:" + metadata.getId(),
                        metadata.getCategory() == null ? "OTHER" : metadata.getCategory().name(),
                        metadata.getCustomerCreatedName(), metadata.getName(), entries);
            } catch (RuntimeException ignored) {
                unavailable++;
            }
        }
        for (Path path : easylistFiles()) {
            try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
                List<String> entries = lines.map(String::trim).filter(this::isRule)
                        .filter(line -> line.toLowerCase(Locale.ROOT).contains(query))
                        .limit(RESULTS_PER_LIST).collect(Collectors.toList());
                addMatch(matches, "rules:" + path.getFileName(), "CONTENT_RULES",
                        path.getFileName().toString(), null, entries);
            } catch (IOException ignored) {
                unavailable++;
            }
        }
        searchFeed(matches, "urls", malwareUrls, true, query);
        searchFeed(matches, "ip4", malwareIps, false, query);
        searchFeed(matches, "ip6", malwareIp6s, false, query);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("matches", matches);
        response.put("limitPerList", RESULTS_PER_LIST);
        response.put("unsearchableLists", unavailable);
        return response;
    }

    private Map<String, Object> item(String id, String category, String label, Map<String, String> name,
                                     Integer size, Instant updated, String version, boolean enabled,
                                     boolean searchable) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("category", category);
        item.put("label", label);
        item.put("name", name);
        item.put("entryCount", size);
        item.put("updatedAt", updated == null ? null : updated.toString());
        item.put("version", version);
        item.put("enabled", enabled);
        item.put("searchable", searchable);
        return item;
    }

    private void addMatch(List<Map<String, Object>> matches, String id, String category,
                          String label, Map<String, String> name, List<String> entries) {
        if (entries.isEmpty()) return;
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("id", id);
        match.put("category", category);
        match.put("label", label);
        match.put("name", name);
        match.put("entries", entries);
        matches.add(match);
    }

    private List<Path> easylistFiles() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(easylistDirectory, "*.txt")) {
            stream.forEach(files::add);
        } catch (IOException ignored) { }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private boolean isRule(String line) {
        return !line.isBlank() && !line.startsWith("!") && !line.startsWith("[");
    }

    private Integer countRules(Path path) {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return (int) lines.map(String::trim).filter(this::isRule).count();
        } catch (IOException ignored) { return null; }
    }

    private Instant modified(Path path) {
        try { return Files.getLastModifiedTime(path).toInstant(); }
        catch (IOException ignored) { return null; }
    }

    private void addFeed(List<Map<String, Object>> lists, String type, Path path, boolean urls) {
        if (!Files.isRegularFile(path)) return;
        lists.add(item("feed:" + type, "MALWARE_FEED", "malware-" + type,
                null, countFeed(path, urls), modified(path), null, true, true));
    }

    private Integer countFeed(Path path, boolean urls) {
        int count = 0;
        try (JsonParser parser = mapper.getFactory().createParser(path.toFile())) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME &&
                        (!urls || "url".equals(parser.getCurrentName()))) count++;
            }
            return count;
        } catch (IOException ignored) { return null; }
    }

    private void searchFeed(List<Map<String, Object>> matches, String type, Path path,
                            boolean urls, String query) {
        if (!Files.isRegularFile(path)) return;
        List<String> entries = new ArrayList<>();
        try (JsonParser parser = mapper.getFactory().createParser(path.toFile())) {
            while (parser.nextToken() != null && entries.size() < RESULTS_PER_LIST) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) continue;
                String value = parser.getCurrentName();
                if (urls) {
                    if (!"url".equals(value) || parser.nextToken() != JsonToken.VALUE_STRING) continue;
                    value = parser.getValueAsString();
                }
                if (value.toLowerCase(Locale.ROOT).contains(query)) entries.add(value);
            }
        } catch (IOException ignored) { return; }
        addMatch(matches, "feed:" + type, "MALWARE_FEED", "malware-" + type, null, entries);
    }
}

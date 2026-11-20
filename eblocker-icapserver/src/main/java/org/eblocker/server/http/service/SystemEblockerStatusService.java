/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2 or subsequent versions.
 */
package org.eblocker.server.http.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.systemstatus.SubSystemDetails;
import org.eblocker.server.common.data.systemstatus.SystemStatusDetails;
import org.eblocker.server.common.ssl.SslService;
import org.eblocker.server.common.update.AutomaticUpdater;
import org.eblocker.server.common.update.SystemUpdater;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, minimal view of existing eBlocker service and protection state. */
@Singleton
public class SystemEblockerStatusService {
    private final SystemStatusService systemStatus;
    private final SystemUpdater updater;
    private final AutomaticUpdater automaticUpdater;
    private final DeviceService devices;
    private final DnsService dns;
    private final SslService ssl;
    private final DataSource dataSource;

    @Inject
    public SystemEblockerStatusService(SystemStatusService systemStatus, SystemUpdater updater,
                                       AutomaticUpdater automaticUpdater, DeviceService devices,
                                       DnsService dns, SslService ssl, DataSource dataSource) {
        this.systemStatus = systemStatus;
        this.updater = updater;
        this.automaticUpdater = automaticUpdater;
        this.devices = devices;
        this.dns = dns;
        this.ssl = ssl;
        this.dataSource = dataSource;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampledAt", Instant.now().toString());
        result.put("executionState", systemStatus.getExecutionState().name());
        List<Map<String, String>> subsystems = new ArrayList<>();
        SystemStatusDetails details = systemStatus.getSystemStatusDetails();
        if (details.getSubSystemDetails() != null) {
            details.getSubSystemDetails().stream()
                    .sorted(Comparator.comparingInt(SubSystemDetails::getOrder))
                    .forEach(detail -> {
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("name", detail.getName());
                        item.put("status", detail.getStatus());
                        subsystems.add(item);
                    });
        }
        result.put("subsystems", subsystems);

        Map<String, Object> updates = new LinkedHashMap<>();
        LocalDateTime lastAttempt = updater.getLastUpdateTime();
        updates.put("lastAttemptAt", lastAttempt == null ? null :
                lastAttempt.atZone(ZoneId.systemDefault()).toInstant().toString());
        updates.put("automaticEnabled", automaticUpdater.isActivated());
        updates.put("installedListsVersion", dataSource.getListsPackageVersion());
        result.put("updates", updates);

        Map<String, Object> protection = new LinkedHashMap<>();
        protection.put("dnsEnabled", dns.isEnabled());
        protection.put("httpsEnabled", ssl.isSslEnabled());
        int known = 0;
        int enabled = 0;
        int ads = 0;
        int trackers = 0;
        int malware = 0;
        for (Device device : devices.getDevices(false)) {
            if (device.isEblocker()) continue;
            known++;
            if (device.isEnabled()) {
                enabled++;
                if (device.isFilterAdsEnabled()) ads++;
                if (device.isFilterTrackersEnabled()) trackers++;
                if (device.isMalwareFilterEnabled()) malware++;
            }
        }
        protection.put("knownDevices", known);
        protection.put("enabledDevices", enabled);
        protection.put("adsEnabledDevices", ads);
        protection.put("trackersEnabledDevices", trackers);
        protection.put("malwareEnabledDevices", malware);
        result.put("protection", protection);
        return result;
    }
}

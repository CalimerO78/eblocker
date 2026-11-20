package org.eblocker.server.http.service;

import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.systemstatus.ExecutionState;
import org.eblocker.server.common.data.systemstatus.SubSystemDetails;
import org.eblocker.server.common.data.systemstatus.SystemStatusDetails;
import org.eblocker.server.common.ssl.SslService;
import org.eblocker.server.common.update.AutomaticUpdater;
import org.eblocker.server.common.update.SystemUpdater;
import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SystemEblockerStatusServiceTest {
    @SuppressWarnings("unchecked")
    @Test
    public void returnsOnlyAggregateDeviceStateAndLabelsUpdateAttempt() {
        SystemStatusService status = mock(SystemStatusService.class);
        SystemUpdater updater = mock(SystemUpdater.class);
        AutomaticUpdater automatic = mock(AutomaticUpdater.class);
        DeviceService devices = mock(DeviceService.class);
        DnsService dns = mock(DnsService.class);
        SslService ssl = mock(SslService.class);
        DataSource data = mock(DataSource.class);
        Device protectedDevice = mock(Device.class);
        Device disabledDevice = mock(Device.class);
        when(status.getExecutionState()).thenReturn(ExecutionState.RUNNING);
        when(status.getSystemStatusDetails()).thenReturn(new SystemStatusDetails(null,
                ExecutionState.RUNNING, List.of(), List.of(
                new SubSystemDetails("ICAP_SERVER", "OK", 3, null, Map.of())), null, null));
        when(updater.getLastUpdateTime()).thenReturn(LocalDateTime.of(2026, 11, 15, 12, 0));
        when(automatic.isActivated()).thenReturn(true);
        when(data.getListsPackageVersion()).thenReturn("20261115073706");
        when(dns.isEnabled()).thenReturn(true);
        when(ssl.isSslEnabled()).thenReturn(true);
        when(protectedDevice.getName()).thenReturn("private-device-name");
        when(protectedDevice.isEnabled()).thenReturn(true);
        when(protectedDevice.isFilterAdsEnabled()).thenReturn(true);
        when(protectedDevice.isMalwareFilterEnabled()).thenReturn(true);
        when(devices.getDevices(false)).thenReturn(List.of(protectedDevice, disabledDevice));

        Map<String, Object> result = new SystemEblockerStatusService(status, updater,
                automatic, devices, dns, ssl, data).snapshot();
        Map<String, Object> protection = (Map<String, Object>) result.get("protection");
        Map<String, Object> updates = (Map<String, Object>) result.get("updates");
        Assert.assertEquals(2, protection.get("knownDevices"));
        Assert.assertEquals(1, protection.get("enabledDevices"));
        Assert.assertEquals(1, protection.get("adsEnabledDevices"));
        Assert.assertEquals(0, protection.get("trackersEnabledDevices"));
        Assert.assertEquals("20261115073706", updates.get("installedListsVersion"));
        Assert.assertNotNull(updates.get("lastAttemptAt"));
        Assert.assertFalse(result.toString().contains("private-device-name"));
    }
}

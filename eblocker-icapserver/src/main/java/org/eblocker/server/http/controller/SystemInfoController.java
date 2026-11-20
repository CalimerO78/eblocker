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
package org.eblocker.server.http.controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.eblocker.server.http.service.DynDnsService;
import org.eblocker.server.http.service.SystemInfoService;
import org.eblocker.server.http.service.SystemListInfoService;
import org.eblocker.server.http.service.SystemEblockerStatusService;
import org.eblocker.server.http.service.SystemLogInfoService;
import org.eblocker.server.http.utils.ControllerUtils;
import org.restexpress.Request;
import org.restexpress.Response;

import java.util.HashMap;
import java.util.Map;

@Singleton
public class SystemInfoController {
    private final SystemInfoService service;
    private final Provider<SystemListInfoService> lists;
    private final Provider<SystemEblockerStatusService> eblocker;
    private final Provider<SystemLogInfoService> logs;
    private final Provider<DynDnsService> dynDns;

    @Inject
    public SystemInfoController(SystemInfoService service, Provider<SystemListInfoService> lists,
                                Provider<SystemEblockerStatusService> eblocker, Provider<SystemLogInfoService> logs,
                                Provider<DynDnsService> dynDns) {
        this.service = service;
        this.lists = lists;
        this.eblocker = eblocker;
        this.logs = logs;
        this.dynDns = dynDns;
    }

    public Map<String, Object> get(Request request, Response response) {
        return service.getSnapshot();
    }

    public Map<String, Object> getEblocker(Request request, Response response) {
        return eblocker.get().snapshot();
    }

    public Map<String, Object> getDynDns(Request request, Response response) {
        DynDnsService service = dynDns.get();
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", service.isEnabled());
        status.put("hostname", service.getHostname());
        // This is the last address reported to eBlocker DynDNS, not a live WAN probe.
        status.put("reportedIpAddress", service.getIpAddress());
        return status;
    }

    public Map<String, Object> getLogs(Request request, Response response) {
        return logs.get().overview();
    }

    public Map<String, Object> readLog(Request request, Response response) {
        return logs.get().read(ControllerUtils.getQueryParameter(request, "file", ""),
                ControllerUtils.getQueryParameter(request, "q", ""));
    }

    public Map<String, Object> getLists(Request request, Response response) {
        return lists.get().overview();
    }

    public Map<String, Object> searchLists(Request request, Response response) {
        return lists.get().search(ControllerUtils.getQueryParameter(request, "q", ""));
    }
}

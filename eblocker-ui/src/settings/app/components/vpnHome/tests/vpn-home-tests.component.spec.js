/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2.
 */
import 'angular-mocks';

describe('App settings; Home VPN diagnostics', function() {
    beforeEach(angular.mock.module('template.settings.app'));
    beforeEach(angular.mock.module('eblocker.adminconsole'));
    beforeEach(angular.mock.module(function($translateProvider) {
        $translateProvider.translations('en', {});
    }));

    let ctrl, httpBackend;
    beforeEach(inject(function($componentController, $httpBackend) {
        ctrl = $componentController('vpnHomeTestsComponent', {}, {});
        httpBackend = $httpBackend;
        httpBackend.whenGET('/api/adminconsole/console/ip').respond(200, 'http://127.0.0.1');
        httpBackend.whenGET('/api/adminconsole/systemstatus').respond(200, {executionState: 'RUNNING'});
        httpBackend.whenGET('/api/settings').respond(200, {});
    }));

    afterEach(function() {
        httpBackend.verifyNoOutstandingExpectation();
        httpBackend.verifyNoOutstandingRequest();
    });

    it('loads independent statuses and preserves unknown on a failed request', function() {
        httpBackend.expectGET('/api/adminconsole/openvpn/status').respond(200, {isRunning: true});
        httpBackend.expectGET('/api/adminconsole/wireguard/status').respond(503);
        httpBackend.expectGET('/api/adminconsole/systeminfo/dyndns')
            .respond(200, {enabled: true, reportedIpAddress: '203.0.113.4'});
        httpBackend.expectGET('/api/adminconsole/ssl/certs/status').respond(200, true);
        ctrl.refresh();
        httpBackend.flush();
        expect(ctrl.status.openvpn.isRunning).toBe(true);
        expect(ctrl.status.wireguard).toBeNull();
        expect(ctrl.status.dyndns.reportedIpAddress).toBe('203.0.113.4');
        expect(ctrl.status.certificate).toBe(true);
    });

    it('shows only dedicated OpenVPN and WireGuard log files', function() {
        httpBackend.expectGET('/api/adminconsole/systeminfo/logs').respond(200, {files: [
            {id: 'syslog'},
            {id: 'eblocker/eblocker.log'},
            {id: 'openvpn/server.log'},
            {id: 'wireguard.log'},
            {id: 'wg0.log'}
        ]});
        ctrl.loadLogs();
        httpBackend.flush();
        expect(ctrl.logFiles.map(function(file) { return file.id; })).toEqual([
            'openvpn/server.log', 'wireguard.log', 'wg0.log'
        ]);
    });

    it('does not issue a log request for search text under three characters', function() {
        ctrl.logFile = 'test.log';
        ctrl.logQuery = 'ab';
        ctrl.readLog();
        expect(ctrl.logResult).toBeNull();
        httpBackend.flush();
    });
});

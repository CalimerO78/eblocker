/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2.
 */
import 'angular-mocks';

describe('App settings; eBlocker Mobile status overview', function() {
    beforeEach(angular.mock.module('template.settings.app'));
    beforeEach(angular.mock.module('eblocker.adminconsole'));
    beforeEach(angular.mock.module(function($translateProvider) {
        $translateProvider.translations('en', {});
    }));

    let ctrl, httpBackend;
    beforeEach(inject(function($componentController, $httpBackend) {
        ctrl = $componentController('vpnHomeOverviewComponent', {}, {});
        httpBackend = $httpBackend;
        httpBackend.whenGET('/api/adminconsole/console/ip').respond(200, 'http://127.0.0.1');
        httpBackend.whenGET('/api/adminconsole/systemstatus').respond(200, {executionState: 'RUNNING'});
        httpBackend.whenGET('/api/settings').respond(200, {});
    }));

    afterEach(function() {
        httpBackend.verifyNoOutstandingExpectation();
        httpBackend.verifyNoOutstandingRequest();
    });

    it('keeps failed sources unknown while showing other statuses', function() {
        httpBackend.expectGET('/api/adminconsole/openvpn/status').respond(200, {isRunning: true});
        httpBackend.expectGET('/api/adminconsole/wireguard/status').respond(503);
        httpBackend.expectGET('/api/adminconsole/systeminfo/dyndns')
            .respond(200, {enabled: true, reportedIpAddress: '203.0.113.4'});
        httpBackend.expectGET('/api/adminconsole/ssl/certs/status').respond(200, true);
        ctrl.refresh();
        httpBackend.flush();
        expect(ctrl.state('openvpn')).toBe('RUNNING');
        expect(ctrl.state('wireguard')).toBe('UNKNOWN');
        expect(ctrl.state('dyndns')).toBe('ENABLED');
        expect(ctrl.state('certificate')).toBe('READY');
        expect(ctrl.status.dyndns.reportedIpAddress).toBe('203.0.113.4');
    });

    it('reports a configured WireGuard server as running only when the interface is up', function() {
        ctrl.status.wireguard = {enabled: true, runtime: {service: 'active', wg: 'up', peers: 2}};
        expect(ctrl.state('wireguard')).toBe('RUNNING');
        ctrl.status.wireguard.runtime.wg = 'down';
        expect(ctrl.state('wireguard')).toBe('ERROR');
        httpBackend.flush();
    });
});

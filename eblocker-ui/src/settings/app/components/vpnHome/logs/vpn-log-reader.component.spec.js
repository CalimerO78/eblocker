/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2.
 */
import 'angular-mocks';

describe('App settings; VPN log reader', function() {
    beforeEach(angular.mock.module('template.settings.app'));
    beforeEach(angular.mock.module('eblocker.adminconsole'));
    beforeEach(angular.mock.module(function($translateProvider) {
        $translateProvider.translations('en', {});
    }));

    let ctrl, httpBackend;
    beforeEach(inject(function($componentController, $httpBackend) {
        ctrl = $componentController('vpnLogReaderComponent', {}, {protocol: 'wireguard'});
        httpBackend = $httpBackend;
        httpBackend.whenGET('/api/adminconsole/console/ip').respond(200, 'http://127.0.0.1');
        httpBackend.whenGET('/api/adminconsole/systemstatus').respond(200, {executionState: 'RUNNING'});
        httpBackend.whenGET('/api/settings').respond(200, {});
    }));

    afterEach(function() {
        httpBackend.verifyNoOutstandingExpectation();
        httpBackend.verifyNoOutstandingRequest();
    });

    it('shows only logs for its own VPN protocol', function() {
        httpBackend.expectGET('/api/adminconsole/systeminfo/logs').respond(200, {files: [
            {id: 'syslog'}, {id: 'openvpn/server.log'}, {id: 'wireguard.log'}, {id: 'wg0.log'}
        ]});
        ctrl.loadLogs();
        httpBackend.flush();
        expect(ctrl.logFiles.map(function(file) { return file.id; }))
            .toEqual(['wireguard.log', 'wg0.log']);
    });

    it('hides the reader when this VPN has no dedicated log', function() {
        ctrl.protocol = 'openvpn';
        httpBackend.expectGET('/api/adminconsole/systeminfo/logs').respond(200, {files: [{id: 'syslog'}]});
        ctrl.loadLogs();
        httpBackend.flush();
        expect(ctrl.logFiles.length).toBe(0);
    });
});

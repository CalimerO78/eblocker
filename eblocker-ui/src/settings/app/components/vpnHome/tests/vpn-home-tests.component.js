/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2.
 */
export default {
    templateUrl: 'app/components/vpnHome/tests/vpn-home-tests.component.html',
    controllerAs: 'vm',
    controller: function($http, $q) {
        'ngInject';
        const vm = this;
        vm.status = {};
        vm.logFiles = [];
        vm.logQuery = '';
        vm.logSerial = 0;

        vm.refresh = function() {
            vm.loading = true;
            const requests = [
                ['/api/adminconsole/openvpn/status', 'openvpn'],
                ['/api/adminconsole/wireguard/status', 'wireguard'],
                ['/api/adminconsole/systeminfo/dyndns', 'dyndns'],
                ['/api/adminconsole/ssl/certs/status', 'certificate']
            ].map(function(entry) {
                return $http.get(entry[0]).then(function(response) {
                    vm.status[entry[1]] = response.data;
                }, function() {
                    vm.status[entry[1]] = null;
                });
            });
            return $q.all(requests).finally(function() { vm.loading = false; });
        };

        // Only dedicated VPN files belong on this page. Generic system logs remain in Systeminfo.
        function isVpnLog(file) {
            return file && /(?:^|[\/._-])(?:openvpn|wireguard|wg-quick|wg[0-9]+)(?=[\/._-]|$)/i
                .test(String(file.id || ''));
        }

        vm.loadLogs = function() {
            vm.logsError = false;
            return $http.get('/api/adminconsole/systeminfo/logs').then(function(response) {
                vm.logFiles = (response.data.files || []).filter(isVpnLog);
                if (!vm.logFiles.some(function(file) { return file.id === vm.logFile; })) {
                    vm.logFile = null;
                    vm.logResult = null;
                    vm.logSerial++;
                }
            }, function() {
                vm.logFiles = [];
                vm.logsError = true;
            });
        };

        vm.readLog = function() {
            const serial = ++vm.logSerial;
            const query = vm.logQuery.trim();
            vm.logResult = null;
            vm.logError = false;
            if (!vm.logFile || (query.length > 0 && query.length < 3)) { return; }
            return $http.get('/api/adminconsole/systeminfo/logs/read',
                {params: {file: vm.logFile, q: query}}).then(function(response) {
                if (serial === vm.logSerial) { vm.logResult = response.data; }
            }, function() {
                if (serial === vm.logSerial) { vm.logError = true; }
            });
        };

        vm.$onInit = function() {
            vm.refresh();
            vm.loadLogs();
        };
    }
};

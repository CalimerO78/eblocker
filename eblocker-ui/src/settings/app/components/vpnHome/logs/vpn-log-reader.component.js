/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2.
 */
export default {
    templateUrl: 'app/components/vpnHome/logs/vpn-log-reader.component.html',
    controllerAs: 'vm',
    bindings: {protocol: '@'},
    controller: function($http) {
        'ngInject';
        const vm = this;
        vm.logFiles = [];
        vm.logQuery = '';
        vm.logSerial = 0;

        function matchesProtocol(file) {
            const id = String((file && file.id) || '');
            const pattern = vm.protocol === 'openvpn' ?
                /(?:^|[\/._-])openvpn(?=[\/._-]|$)/i :
                /(?:^|[\/._-])(?:wireguard|wg-quick|wg[0-9]+)(?=[\/._-]|$)/i;
            return pattern.test(id);
        }

        vm.loadLogs = function() {
            return $http.get('/api/adminconsole/systeminfo/logs').then(function(response) {
                vm.logFiles = (response.data.files || []).filter(matchesProtocol);
                if (!vm.logFiles.some(function(file) { return file.id === vm.logFile; })) {
                    vm.logFile = null;
                    vm.logResult = null;
                    vm.logSerial++;
                }
            }, function() {
                vm.logFiles = [];
                vm.logFile = null;
                vm.logResult = null;
                vm.logSerial++;
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

        vm.$onInit = vm.loadLogs;
    }
};

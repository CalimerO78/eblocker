/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2.
 */
export default {
    templateUrl: 'app/components/vpnHome/overview/vpn-home-overview.component.html',
    controllerAs: 'vm',
    controller: function($http, $q) {
        'ngInject';
        const vm = this;
        vm.status = {};

        function wireGuardState(value) {
            if (!value.enabled) { return 'STOPPED'; }
            const runtime = value.runtime || {};
            if (runtime.error) { return 'ERROR'; }
            if (runtime.service === 'active' && runtime.wg === 'up') { return 'RUNNING'; }
            return runtime.service && runtime.service !== 'unknown' ? 'ERROR' : 'UNKNOWN';
        }

        const stateHandlers = {
            openvpn: function(value) { return value.isRunning === true ? 'RUNNING' : 'STOPPED'; },
            wireguard: wireGuardState,
            dyndns: function(value) { return value.enabled === true ? 'ENABLED' : 'STOPPED'; },
            certificate: function(value) { return value === true ? 'READY' : 'NOT_READY'; }
        };

        vm.state = function(kind) {
            const value = vm.status[kind];
            if (value === null || value === undefined || !stateHandlers[kind]) { return 'UNKNOWN'; }
            return stateHandlers[kind](value);
        };

        vm.refresh = function() {
            vm.loading = true;
            vm.status = {};
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
            return $q.all(requests).finally(function() {
                vm.loading = false;
                vm.checkedAt = Date.now();
            });
        };

        vm.$onInit = vm.refresh;
    }
};

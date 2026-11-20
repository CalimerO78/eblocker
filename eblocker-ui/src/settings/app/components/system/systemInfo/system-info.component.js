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
export default {
    templateUrl: 'app/components/system/systemInfo/system-info.component.html',
    controllerAs: 'vm',
    controller: function($http, $interval, $translate, $window) {
        'ngInject';
        const vm = this;
        vm.meterTicks = new Array(16);
        vm.activeView = 'overview';
        vm.query = '';
        vm.searchSerial = 0;
        vm.expandedLists = {};
        vm.expandedCategories = {};
        Object.assign(vm, {expandedLogs: {}, logQueries: {}, logResults: {},
            logErrors: {}, logSerials: {}, searchExpanded: {}});
        try {
            vm.expandedLists = JSON.parse($window.localStorage.getItem('systeminfo.expandedLists.v1') || '{}');
            if (!vm.expandedLists || Array.isArray(vm.expandedLists)) { vm.expandedLists = {}; }
        } catch (ignored) { vm.expandedLists = {}; }

        try {
            vm.expandedCategories = JSON.parse(
                $window.localStorage.getItem('systeminfo.expandedCategories.v1') || '{}');
            if (!vm.expandedCategories || Array.isArray(vm.expandedCategories)) { vm.expandedCategories = {}; }
        } catch (ignored) { vm.expandedCategories = {}; }

        vm.categoryExpanded = function(name) {
            return vm.expandedCategories[name] === true;
        };

        vm.toggleCategory = function(name) {
            vm.expandedCategories[name] = !vm.categoryExpanded(name);
            try {
                $window.localStorage.setItem('systeminfo.expandedCategories.v1', JSON.stringify(vm.expandedCategories));
            } catch (ignored) { /* Browser storage may be disabled. */ }
        };

        vm.logExpanded = function(id) { return vm.expandedLogs[id] === true; };
        vm.toggleLog = function(id) {
            vm.expandedLogs[id] = !vm.logExpanded(id);
            if (vm.expandedLogs[id] && !vm.logResults[id]) { vm.readLog(id); }
        };

        vm.loadLogs = function() {
            vm.logsLoading = true;
            vm.logsError = false;
            return $http.get('/api/adminconsole/systeminfo/logs').then(function(response) {
                vm.logFiles = response.data.files;
                vm.logInaccessible = response.data.inaccessible || [];
            }, function() { vm.logsError = true; }).finally(function() {
                vm.logsLoading = false;
            });
        };

        vm.readLog = function(id) {
            const query = (vm.logQueries[id] || '').trim();
            const serial = (vm.logSerials[id] || 0) + 1;
            vm.logSerials[id] = serial;
            if (query.length > 0 && query.length < 3) {
                vm.logResults[id] = null;
                return;
            }
            vm.logErrors[id] = false;
            return $http.get('/api/adminconsole/systeminfo/logs/read', {params: {file: id, q: query}})
                .then(function(response) {
                    if (vm.logSerials[id] === serial) { vm.logResults[id] = response.data; }
                }, function() {
                    if (vm.logSerials[id] === serial) { vm.logErrors[id] = true; }
                });
        };

        vm.listExpanded = function(id, searchResult) {
            return searchResult ? vm.searchExpanded[id] === true : vm.expandedLists[id] === true;
        };

        vm.toggleList = function(id, searchResult) {
            if (searchResult) {
                vm.searchExpanded[id] = !vm.listExpanded(id, true);
                return;
            }
            vm.expandedLists[id] = !vm.listExpanded(id, false);
            try {
                $window.localStorage.setItem('systeminfo.expandedLists.v1', JSON.stringify(vm.expandedLists));
            } catch (ignored) { /* Browsers may disable local storage. */ }
        };

        vm.loadEblocker = function() {
            vm.eblockerLoading = true;
            vm.eblockerError = false;
            return $http.get('/api/adminconsole/systeminfo/eblocker').then(function(response) {
                vm.eblockerInfo = response.data;
            }, function() { vm.eblockerError = true; }).finally(function() {
                vm.eblockerLoading = false;
            });
        };

        vm.selectView = function(view) {
            vm.activeView = view;
            if (view === 'lists' && !vm.listInfo) { vm.loadLists(); }
            if (view === 'eblocker' && !vm.eblockerInfo) { vm.loadEblocker(); }
            if (view === 'logs' && !vm.logFiles) { vm.loadLogs(); }
        };

        vm.listName = function(item) {
            if (item.label) { return item.label; }
            const language = $translate.use() || 'de';
            return item.name && (item.name[language] || item.name.de || item.name.en) || item.id;
        };

        vm.loadLists = function() {
            vm.listsLoading = true;
            vm.listsError = false;
            return $http.get('/api/adminconsole/systeminfo/lists').then(function(response) {
                vm.listInfo = response.data;
                vm.listCategories = [];
                response.data.lists.forEach(function(item) {
                    let category = vm.listCategories.filter(function(group) {
                        return group.name === item.category;
                    })[0];
                    if (!category) {
                        category = {name: item.category, items: []};
                        vm.listCategories.push(category);
                    }
                    category.items.push(item);
                });
            }, function() { vm.listsError = true; }).finally(function() {
                vm.listsLoading = false;
            });
        };

        vm.searchLists = function() {
            const query = (vm.query || '').trim();
            const serial = ++vm.searchSerial;
            vm.searchResult = null;
            vm.searchExpanded = {};
            vm.searchError = false;
            if (query.length < 3) { vm.searching = false; return; }
            vm.searching = true;
            return $http.get('/api/adminconsole/systeminfo/lists/search', {params: {q: query}})
                .then(function(response) {
                    if (serial === vm.searchSerial) { vm.searchResult = response.data; }
                }, function() {
                    if (serial === vm.searchSerial) { vm.searchError = true; }
                }).finally(function() {
                    if (serial === vm.searchSerial) { vm.searching = false; }
                });
        };

        vm.refresh = function() {
            if (vm.loading) { return; }
            vm.loading = true;
            vm.error = false;
            return $http.get('/api/adminconsole/systeminfo').then(function(response) {
                const previous = vm.info;
                vm.info = response.data;
                vm.addNetworkRates(previous, vm.info);
                vm.memoryPercent = vm.percent(vm.info.memory.usedBytes, vm.info.memory.totalBytes);
                vm.mainDisk = vm.info.storage.filter(function(disk) {
                    return disk.mount === '/';
                })[0] || vm.info.storage[0];
                vm.diskPercent = vm.mainDisk ? vm.percent(
                    vm.mainDisk.totalBytes - vm.mainDisk.availableBytes, vm.mainDisk.totalBytes) : null;
            }, function() {
                vm.error = true;
            }).finally(function() {
                vm.loading = false;
            });
        };

        vm.addNetworkRates = function(previous, current) {
            const elapsed = previous ?
                (Date.parse(current.sampledAt) - Date.parse(previous.sampledAt)) / 1000 : 0;
            current.network.forEach(function(adapter) {
                adapter.rxRate = null;
                adapter.txRate = null;
                adapter.rxPercent = null;
                adapter.txPercent = null;
                if (!previous || elapsed <= 0) { return; }
                const old = previous.network.filter(function(item) {
                    return item.name === adapter.name;
                })[0];
                if (!old || adapter.rxBytes === null || adapter.txBytes === null ||
                    old.rxBytes === null || old.txBytes === null) { return; }
                const rxDifference = adapter.rxBytes - old.rxBytes;
                const txDifference = adapter.txBytes - old.txBytes;
                if (rxDifference < 0 || txDifference < 0) { return; }
                adapter.rxRate = rxDifference / elapsed;
                adapter.txRate = txDifference / elapsed;
                if (adapter.speedMbps && adapter.speedMbps > 0) {
                    const capacity = adapter.speedMbps * 1000000 / 8;
                    adapter.rxPercent = vm.percent(adapter.rxRate, capacity);
                    adapter.txPercent = vm.percent(adapter.txRate, capacity);
                }
            });
        };

        vm.formatRate = function(bytesPerSecond) {
            if (bytesPerSecond < 1024) { return bytesPerSecond.toFixed(0) + ' B/s'; }
            if (bytesPerSecond < 1048576) { return (bytesPerSecond / 1024).toFixed(1) + ' KiB/s'; }
            return (bytesPerSecond / 1048576).toFixed(1) + ' MiB/s';
        };

        vm.percent = function(part, total) {
            if (part === null || part === undefined || !total || total <= 0) { return null; }
            return Math.max(0, Math.min(100, part * 100 / total));
        };

        vm.meterActive = function(percent, index) {
            return percent !== null && percent !== undefined && index < Math.ceil(percent * vm.meterTicks.length / 100);
        };

        vm.formatBytes = function(bytes) {
            if (bytes === null || bytes === undefined || bytes < 0) {
                return null;
            }
            if (bytes >= 1073741824) {
                return (bytes / 1073741824).toFixed(1) + ' GiB';
            }
            return (bytes / 1048576).toFixed(0) + ' MiB';
        };

        vm.formatUptime = function(seconds) {
            if (seconds === null || seconds === undefined) {
                return null;
            }
            const days = Math.floor(seconds / 86400);
            const hours = Math.floor((seconds % 86400) / 3600);
            const minutes = Math.floor((seconds % 3600) / 60);
            return days + 'd ' + hours + 'h ' + minutes + 'm';
        };

        vm.$onInit = function() {
            vm.refresh();
            vm.refreshTimer = $interval(vm.refresh, 10000);
        };
        vm.$onDestroy = function() {
            if (vm.refreshTimer) { $interval.cancel(vm.refreshTimer); }
        };
    }
};

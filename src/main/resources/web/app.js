(function () {
    'use strict';

    var API = window.location.origin;
    var app = document.getElementById('app');
    var currentPage = null;
    var modules = [];

    // ── Helpers ──

    function getToken() {
        return localStorage.getItem('xcore_token') || '';
    }

    function setToken(token) {
        localStorage.setItem('xcore_token', token);
    }

    function clearToken() {
        localStorage.removeItem('xcore_token');
    }

    function api(path) {
        var headers = {};
        var token = getToken();
        if (token) {
            headers['Authorization'] = 'Bearer ' + token;
        }
        return fetch(API + path, { headers: headers })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            });
    }

    function apiPost(path, body) {
        return fetch(API + path, {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + getToken(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(body)
        }).then(function (res) {
            if (!res.ok) {
                return res.json().then(function (err) { throw err; }, function () { throw new Error('HTTP ' + res.status); });
            }
            return res.json();
        });
    }

    function el(tag, attrs, children) {
        var node = document.createElement(tag);
        if (attrs) {
            Object.keys(attrs).forEach(function (k) {
                if (k === 'className') node.className = attrs[k];
                else if (k === 'textContent') node.textContent = attrs[k];
                else if (k === 'innerHTML') node.innerHTML = attrs[k];
                else if (k.indexOf('on') === 0) node.addEventListener(k.slice(2).toLowerCase(), attrs[k]);
                else node.setAttribute(k, attrs[k]);
            });
        }
        if (children) {
            children.forEach(function (c) {
                if (typeof c === 'string') node.appendChild(document.createTextNode(c));
                else if (c) node.appendChild(c);
            });
        }
        return node;
    }

    function capitalize(str) {
        return str.replace(/_/g, ' ').replace(/\b\w/g, function (c) { return c.toUpperCase(); });
    }

    function formatNumber(val) {
        if (typeof val !== 'number') return val;
        return val.toLocaleString();
    }

    function isDateString(val) {
        if (typeof val !== 'string' || val.length < 10) return false;
        return /^\d{4}-\d{2}-\d{2}/.test(val);
    }

    function formatDate(val) {
        try {
            var d = new Date(val);
            if (isNaN(d.getTime())) return val;
            return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
        } catch (e) {
            return val;
        }
    }

    function formatDateTime(val) {
        try {
            var d = new Date(val);
            if (isNaN(d.getTime())) return val;
            return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
                + ' ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
        } catch (e) {
            return val;
        }
    }

    function formatCellValue(key, val) {
        if (val === null || val === undefined) return '-';
        if (typeof val === 'number') return formatNumber(val);
        if (isDateString(String(val))) return formatDate(String(val));
        return String(val);
    }

    function formatMaterialName(val) {
        if (!val || typeof val !== 'string') return '-';
        return val.replace(/_/g, ' ').replace(/\b\w/g, function (c) { return c.toUpperCase(); });
    }

    function isPermanent(val) {
        return val === null || val === undefined || val === '' || val === 'none'
            || val === 'null' || val === '-1' || val === -1 || val === 'permanent'
            || val === 'Permanent';
    }

    function formatExpiration(val) {
        if (isPermanent(val)) return 'Permanent';
        return isDateString(String(val)) ? formatDateTime(String(val)) : String(val);
    }

    // ── Toast notifications ──

    function showToast(message, type) {
        var container = document.getElementById('toast-container');
        var toast = el('div', { className: 'toast toast-' + (type || 'info'), textContent: message });
        container.appendChild(toast);
        setTimeout(function () {
            if (toast.parentNode) toast.parentNode.removeChild(toast);
        }, 3000);
    }

    // ── Confirmation dialog ──

    function confirmAction(message, onConfirm) {
        var overlay = document.getElementById('modal-overlay');
        overlay.innerHTML = '';
        overlay.classList.remove('hidden');

        var card = el('div', { className: 'modal-card' });
        card.appendChild(el('h3', { textContent: 'Confirm Action' }));
        card.appendChild(el('p', { textContent: message }));

        var actions = el('div', { className: 'modal-actions' });
        actions.appendChild(el('button', {
            className: 'btn btn-secondary',
            textContent: 'Cancel',
            onClick: function () { overlay.classList.add('hidden'); }
        }));
        actions.appendChild(el('button', {
            className: 'btn btn-danger',
            textContent: 'Confirm',
            onClick: function () {
                overlay.classList.add('hidden');
                onConfirm();
            }
        }));
        card.appendChild(actions);
        overlay.appendChild(card);
    }

    // ── Pagination renderer ──

    function renderPagination(container, currentPg, totalPages, onPageChange) {
        var pag = el('div', { className: 'pagination' });

        var prevBtn = el('button', {
            className: 'page-btn',
            textContent: 'Previous',
            onClick: function () { if (currentPg > 1) onPageChange(currentPg - 1); }
        });
        if (currentPg <= 1) prevBtn.disabled = true;
        pag.appendChild(prevBtn);

        var startPage = Math.max(1, currentPg - 2);
        var endPage = Math.min(totalPages, startPage + 4);
        if (endPage - startPage < 4) startPage = Math.max(1, endPage - 4);

        for (var i = startPage; i <= endPage; i++) {
            (function (page) {
                var btn = el('button', {
                    className: 'page-btn' + (page === currentPg ? ' active' : ''),
                    textContent: String(page),
                    onClick: function () { onPageChange(page); }
                });
                pag.appendChild(btn);
            })(i);
        }

        if (endPage < totalPages) {
            pag.appendChild(el('span', { className: 'page-info', textContent: '...' }));
            pag.appendChild(el('button', {
                className: 'page-btn',
                textContent: String(totalPages),
                onClick: function () { onPageChange(totalPages); }
            }));
        }

        var nextBtn = el('button', {
            className: 'page-btn',
            textContent: 'Next',
            onClick: function () { if (currentPg < totalPages) onPageChange(currentPg + 1); }
        });
        if (currentPg >= totalPages) nextBtn.disabled = true;
        pag.appendChild(nextBtn);

        container.appendChild(pag);
    }

    // Map of action values to badge classes
    var actionBadgeMap = {
        'BUY': 'badge-green',
        'PURCHASE': 'badge-green',
        'SELL': 'badge-red',
        'SOLD': 'badge-red',
        'BAN': 'badge-red',
        'UNBAN': 'badge-green',
        'MUTE': 'badge-orange',
        'UNMUTE': 'badge-green',
        'WARN': 'badge-yellow',
        'KICK': 'badge-red',
        'REPORT': 'badge-blue',
        'TEMPBAN': 'badge-red',
        'TEMPMUTE': 'badge-orange',
        'IPBAN': 'badge-red',
        'IPMUTE': 'badge-orange',
        'JAIL': 'badge-purple',
        'UNJAIL': 'badge-green',
        'UNWARN': 'badge-green',
        'UNIPBAN': 'badge-green',
        'UNIPMUTE': 'badge-green'
    };

    var listingTypeBadgeMap = {
        'FIXED_PRICE': { cls: 'badge-green', label: 'Buy Now' },
        'AUCTION': { cls: 'badge-purple', label: 'Auction' },
        'BUY_NOW': { cls: 'badge-green', label: 'Buy Now' }
    };

    // Check if a column key looks like a player name field
    function isPlayerColumn(key) {
        var lower = key.toLowerCase();
        return lower === 'player_name' || lower === 'playername'
            || lower === 'seller' || lower === 'seller_name'
            || lower === 'buyer' || lower === 'buyer_name'
            || lower === 'sender' || lower === 'sender_name'
            || lower === 'target' || lower === 'target_name'
            || lower === 'reporter' || lower === 'reported';
    }

    // Returns a DOM node for special columns, or null for default text handling
    function formatCellSpecial(key, val) {
        var lowerKey = key.toLowerCase();

        // Action column -> badge
        if (lowerKey === 'action' || lowerKey === 'type' || lowerKey === 'punishment_type') {
            var upper = String(val).toUpperCase();
            var badgeCls = actionBadgeMap[upper];
            if (badgeCls) {
                return el('span', { className: 'badge ' + badgeCls, textContent: upper });
            }
        }

        // Listing type -> badge
        if (lowerKey === 'listing_type' || lowerKey === 'listingtype' || lowerKey === 'sale_type') {
            var upperLt = String(val).toUpperCase().replace(/ /g, '_');
            var ltInfo = listingTypeBadgeMap[upperLt];
            if (ltInfo) {
                return el('span', { className: 'badge ' + ltInfo.cls, textContent: ltInfo.label });
            }
        }

        // Active/status column -> colored badge
        if (lowerKey === 'active' || lowerKey === 'status') {
            var sVal = String(val).toLowerCase();
            if (sVal === 'true' || sVal === '1' || sVal === 'active' || sVal === 'yes') {
                return el('span', { className: 'badge badge-green', textContent: 'Active' });
            } else if (sVal === 'false' || sVal === '0' || sVal === 'expired' || sVal === 'no' || sVal === 'inactive') {
                return el('span', { className: 'badge badge-red', textContent: 'Expired' });
            }
        }

        // Expiration column -> "Permanent" if empty/null/none
        if (lowerKey === 'expiration' || lowerKey === 'expires' || lowerKey === 'expire_date'
            || lowerKey === 'end_date' || lowerKey === 'duration') {
            if (isPermanent(val)) {
                return el('span', { className: 'badge badge-red', textContent: 'Permanent' });
            }
        }

        // Material/item column -> formatted name
        if (lowerKey === 'material' || lowerKey === 'item_id' || lowerKey === 'item_material'
            || lowerKey === 'item_type' || lowerKey === 'itemtype') {
            return document.createTextNode(formatMaterialName(String(val)));
        }

        // Server column -> "Global" if empty/null
        if (lowerKey === 'server' || lowerKey === 'server_name') {
            if (val === null || val === undefined || val === '' || val === 'null' || val === 'global') {
                return el('span', { className: 'badge badge-blue', textContent: 'Global' });
            }
        }

        // Reason column -> truncated with tooltip
        if (lowerKey === 'reason') {
            var reasonStr = String(val);
            if (reasonStr.length > 60) {
                return el('span', { className: 'truncated', title: reasonStr, textContent: reasonStr });
            }
        }

        return null;
    }

    function playerAvatar(name, size) {
        size = size || 24;
        return 'https://mc-heads.net/avatar/' + encodeURIComponent(name) + '/' + size;
    }

    function makePlayerLink(name) {
        return el('span', { className: 'player-cell' }, [
            el('img', {
                src: playerAvatar(name, 24),
                width: '24', height: '24',
                style: 'border-radius: 4px;'
            }),
            el('a', {
                className: 'player-link',
                textContent: name,
                onClick: function (e) {
                    e.preventDefault();
                    navigateTo('player:' + name);
                }
            })
        ]);
    }

    // ── Stat card helper ──

    function addStat(container, label, value, colorClass) {
        var cls = 'stat-card';
        if (colorClass) cls += ' ' + colorClass;
        var card = el('div', { className: cls });
        card.appendChild(el('div', { className: 'stat-label', textContent: label }));
        card.appendChild(el('div', { className: 'stat-value', textContent: String(value) }));
        container.appendChild(card);
    }

    // ── Bar chart helper ──

    function renderBarChart(container, data, labelKey, valueKey) {
        var chart = el('div', { className: 'bar-chart' });
        if (!data || data.length === 0) {
            container.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No chart data available.' })]));
            return;
        }
        var maxVal = 0;
        data.forEach(function (d) { if (d[valueKey] > maxVal) maxVal = d[valueKey]; });
        if (maxVal === 0) maxVal = 1;

        data.forEach(function (d) {
            var col = el('div', { className: 'bar-chart-col' });
            var heightPct = Math.max(2, (d[valueKey] / maxVal) * 100);
            var bar = el('div', {
                className: 'bar-chart-bar',
                style: 'height: ' + heightPct + '%'
            });
            bar.appendChild(el('span', { className: 'bar-tooltip', textContent: formatNumber(d[valueKey]) }));
            col.appendChild(bar);
            var labelText = String(d[labelKey] || '');
            if (labelText.length > 10) labelText = labelText.substring(5);
            col.appendChild(el('div', { className: 'bar-chart-label', textContent: labelText }));
            chart.appendChild(col);
        });
        container.appendChild(chart);
    }

    // ── Toggle switch helper ──

    function makeToggle(checked, onChange) {
        var label = el('label', { className: 'toggle-switch' });
        var input = el('input', { type: 'checkbox' });
        if (checked) input.checked = true;
        input.addEventListener('change', function () { onChange(input.checked); });
        var slider = el('span', { className: 'toggle-slider' });
        label.appendChild(input);
        label.appendChild(slider);
        return label;
    }

    // ── Tab bar helper ──

    function renderTabBar(container, tabs, activeTab, onTabChange) {
        var bar = el('div', { className: 'tab-bar' });
        tabs.forEach(function (tab) {
            bar.appendChild(el('button', {
                className: 'tab-btn' + (tab.id === activeTab ? ' active' : ''),
                textContent: tab.label,
                onClick: function () { onTabChange(tab.id); }
            }));
        });
        container.appendChild(bar);
    }

    // ── Search bar helper ──

    function renderSearchBar(container, opts) {
        var bar = el('div', { className: 'search-bar' });
        var input = el('input', { type: 'text', placeholder: opts.placeholder || 'Search...' });
        if (opts.value) input.value = opts.value;
        bar.appendChild(input);

        if (opts.filters) {
            opts.filters.forEach(function (f) {
                var select = el('select');
                select.appendChild(el('option', { value: '', textContent: f.placeholder || 'All' }));
                f.options.forEach(function (o) {
                    var optEl = el('option', { value: o.value, textContent: o.label });
                    if (opts.filterValues && opts.filterValues[f.key] === o.value) optEl.selected = true;
                    select.appendChild(optEl);
                });
                select.dataset.filterKey = f.key;
                bar.appendChild(select);
            });
        }

        var searchBtn = el('button', {
            className: 'btn',
            textContent: 'Search',
            onClick: function () {
                var filters = {};
                bar.querySelectorAll('select').forEach(function (s) {
                    filters[s.dataset.filterKey] = s.value;
                });
                opts.onSearch(input.value.trim(), filters);
            }
        });
        bar.appendChild(searchBtn);

        if (opts.onSearch) {
            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') searchBtn.click();
            });
        }

        container.appendChild(bar);
        return { input: input, bar: bar };
    }


    // ══════════════════════════════════════════════════════════════
    //  LOGIN PAGE
    // ══════════════════════════════════════════════════════════════

    function renderLogin(errorMsg) {
        app.innerHTML = '';
        var wrapper = el('div', { className: 'login-wrapper' });
        var card = el('div', { className: 'login-card' });

        card.appendChild(el('h1', { textContent: 'XCore Dashboard' }));
        card.appendChild(el('p', { textContent: 'Enter your API token to access the dashboard.' }));

        var form = el('div', { className: 'form-group' });
        form.appendChild(el('label', { textContent: 'API Token', for: 'token-input' }));
        var input = el('input', { type: 'text', id: 'token-input', placeholder: 'Paste your token here...' });
        form.appendChild(input);
        card.appendChild(form);

        var errEl = el('div', { className: 'login-error', id: 'login-error' });
        if (errorMsg) {
            errEl.textContent = errorMsg;
            errEl.style.display = 'block';
        }

        var btn = el('button', {
            className: 'btn',
            textContent: 'Connect',
            onClick: function () {
                var val = input.value.trim();
                if (!val) {
                    errEl.textContent = 'Please enter a token.';
                    errEl.style.display = 'block';
                    return;
                }
                setToken(val);
                api('/api/auth').then(function () {
                    renderDashboard();
                }).catch(function () {
                    clearToken();
                    errEl.textContent = 'Invalid token or server unreachable.';
                    errEl.style.display = 'block';
                });
            }
        });

        card.appendChild(btn);
        card.appendChild(errEl);
        wrapper.appendChild(card);
        app.appendChild(wrapper);

        input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') btn.click();
        });

        input.focus();
    }


    // ══════════════════════════════════════════════════════════════
    //  DASHBOARD SHELL
    // ══════════════════════════════════════════════════════════════

    function renderDashboard() {
        app.innerHTML = '';
        var dash = el('div', { className: 'dashboard' });

        // Sidebar
        var sidebar = el('div', { className: 'sidebar' });

        var header = el('div', { className: 'sidebar-header' });
        header.appendChild(el('h2', { textContent: 'XCore' }));
        header.appendChild(el('span', { className: 'sidebar-badge', textContent: 'Server' }));
        sidebar.appendChild(header);

        var nav = el('div', { className: 'sidebar-nav', id: 'sidebar-nav' });
        sidebar.appendChild(nav);

        var footer = el('div', { className: 'sidebar-footer' });
        footer.appendChild(el('button', {
            className: 'btn-logout',
            textContent: 'Logout',
            onClick: function () {
                clearToken();
                renderLogin();
            }
        }));
        sidebar.appendChild(footer);

        // Main content
        var main = el('div', { className: 'main-content', id: 'main-content' });

        dash.appendChild(sidebar);
        dash.appendChild(main);
        app.appendChild(dash);

        buildSidebar();
        navigateTo('overview');
    }

    function buildSidebar() {
        var nav = document.getElementById('sidebar-nav');
        nav.innerHTML = '';

        // Core section
        var coreSection = el('div', { className: 'sidebar-section' });
        coreSection.appendChild(el('div', { className: 'sidebar-section-title', textContent: 'Core' }));
        coreSection.appendChild(makeLink('Overview', 'overview'));
        coreSection.appendChild(makeLink('Players', 'players'));
        nav.appendChild(coreSection);

        // Load modules
        api('/api/modules').then(function (mods) {
            modules = mods || [];
            modules.forEach(function (mod) {
                if (!mod.pages || mod.pages.length === 0) return;
                var modSection = el('div', { className: 'sidebar-section' });
                modSection.appendChild(el('div', { className: 'sidebar-section-title', textContent: mod.name }));
                mod.pages.forEach(function (page) {
                    var id = 'module:' + mod.name + ':' + page.path;
                    modSection.appendChild(makeLink(page.name || page.path, id));
                });
                nav.appendChild(modSection);
            });
        }).catch(function () {
            // Modules endpoint may not be available
        });
    }

    function makeLink(text, pageId) {
        var link = el('a', {
            className: 'sidebar-link' + (currentPage === pageId ? ' active' : ''),
            textContent: text,
            onClick: function (e) {
                e.preventDefault();
                navigateTo(pageId);
            }
        });
        link.dataset.page = pageId;
        return link;
    }

    function setActiveLink(pageId) {
        var links = document.querySelectorAll('.sidebar-link');
        links.forEach(function (l) {
            l.classList.toggle('active', l.dataset.page === pageId);
        });
    }


    // ══════════════════════════════════════════════════════════════
    //  ROUTING
    // ══════════════════════════════════════════════════════════════

    function navigateTo(pageId) {
        currentPage = pageId;
        setActiveLink(pageId);

        if (pageId === 'overview') { loadOverview(); return; }
        if (pageId === 'players') { loadPlayers(); return; }
        if (pageId.indexOf('player:') === 0) { loadPlayerProfile(pageId.split(':')[1]); return; }

        if (pageId.indexOf('module:') === 0) {
            var parts = pageId.split(':');
            var modName = parts[1];
            var pagePath = parts[2] || '';

            // Check for dedicated renderer
            if (moduleRenderers[modName] && moduleRenderers[modName][pagePath]) {
                moduleRenderers[modName][pagePath]();
                return;
            }

            // Fallback to generic
            loadModule(modName, pagePath);
        }
    }


    // ══════════════════════════════════════════════════════════════
    //  OVERVIEW PAGE
    // ══════════════════════════════════════════════════════════════

    function loadOverview() {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: 'Overview' }));

        var grid = el('div', { className: 'stats-grid', id: 'stats-grid' });
        main.appendChild(grid);
        grid.innerHTML = '<div class="loading">Loading stats...</div>';

        var recentCard = el('div', { className: 'card', id: 'recent-players-card' });
        recentCard.appendChild(el('h3', { textContent: 'Recent Players' }));
        recentCard.appendChild(el('div', { className: 'loading', textContent: 'Loading...' }));
        main.appendChild(recentCard);

        api('/api/metrics').then(function (s) {
            grid.innerHTML = '';

            if (s.server_name) {
                addStat(grid, 'Server', s.server_name, '');
            }

            addStat(grid, 'Online Players', s.players_online != null ? s.players_online : '-', 'green');
            addStat(grid, 'Modules', s.modules_count != null ? s.modules_count : '-', 'purple');
            addStat(grid, 'Uptime', formatUptime(s.uptime_seconds ? s.uptime_seconds * 1000 : null), 'cyan');

            if (s.tps != null) {
                var tpsVal = typeof s.tps === 'number' ? s.tps.toFixed(1) : s.tps;
                var tpsColor = 'green';
                if (typeof s.tps === 'number') {
                    if (s.tps < 15) tpsColor = 'red';
                    else if (s.tps < 18) tpsColor = 'orange';
                }
                addStat(grid, 'TPS', tpsVal, tpsColor);
            }

            if (s.memory_used != null && s.memory_max != null) {
                var memUsed = Math.round(s.memory_used / 1024 / 1024);
                var memMax = Math.round(s.memory_max / 1024 / 1024);
                var memPct = Math.round((s.memory_used / s.memory_max) * 100);
                var memColor = memPct > 85 ? 'red' : memPct > 65 ? 'orange' : 'green';
                addStat(grid, 'Memory', memUsed + ' / ' + memMax + ' MB', memColor);
            } else if (s.memory_used != null) {
                addStat(grid, 'Memory', Math.round(s.memory_used / 1024 / 1024) + ' MB', 'orange');
            }
        }).catch(function () {
            grid.innerHTML = '';
            grid.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load stats. Check your token or server connection.' }));
        });

        // Load recent players
        api('/api/players?offset=0&limit=5').then(function (data) {
            var players = data.players || data;
            var rCard = document.getElementById('recent-players-card');
            if (!rCard) return;
            rCard.innerHTML = '';
            rCard.appendChild(el('h3', { textContent: 'Recent Players' }));

            if (!players || players.length === 0) {
                rCard.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No players found.' })]));
                return;
            }

            var wrapper = el('div', { className: 'table-wrapper' });
            var table = el('table');
            var thead = el('thead');
            var headRow = el('tr');
            headRow.appendChild(el('th', { textContent: 'Player' }));
            headRow.appendChild(el('th', { textContent: 'Last Seen' }));
            thead.appendChild(headRow);
            table.appendChild(thead);

            var tbody = el('tbody');
            players.forEach(function (p) {
                var name = p.player_name || p.name || '';
                var row = el('tr');
                var nameCell = el('td');
                nameCell.appendChild(makePlayerLink(name));
                row.appendChild(nameCell);
                var lastLogin = p.last_login || p.lastLogin || '-';
                row.appendChild(el('td', { textContent: isDateString(lastLogin) ? formatDate(lastLogin) : lastLogin }));
                tbody.appendChild(row);
            });
            table.appendChild(tbody);
            wrapper.appendChild(table);
            rCard.appendChild(wrapper);
        }).catch(function () {
            var rCard = document.getElementById('recent-players-card');
            if (rCard) {
                rCard.innerHTML = '';
                rCard.appendChild(el('h3', { textContent: 'Recent Players' }));
                rCard.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'Could not load recent players.' })]));
            }
        });
    }

    function formatUptime(ms) {
        if (ms == null) return '-';
        var s = Math.floor(ms / 1000);
        var d = Math.floor(s / 86400);
        s %= 86400;
        var h = Math.floor(s / 3600);
        s %= 3600;
        var m = Math.floor(s / 60);
        if (d > 0) return d + 'd ' + h + 'h ' + m + 'm';
        if (h > 0) return h + 'h ' + m + 'm';
        return m + 'm';
    }


    // ══════════════════════════════════════════════════════════════
    //  PLAYERS PAGE
    // ══════════════════════════════════════════════════════════════

    function loadPlayers() {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: 'Players' }));

        var card = el('div', { className: 'card' });
        card.innerHTML = '<div class="loading">Loading players...</div>';
        main.appendChild(card);

        api('/api/players?offset=0&limit=100').then(function (data) {
            var players = data.players || data;
            if (!players || players.length === 0) {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No players found.' })]));
                return;
            }

            card.innerHTML = '';
            var wrapper = el('div', { className: 'table-wrapper' });
            var table = el('table');
            var thead = el('thead');
            var headRow = el('tr');
            headRow.appendChild(el('th', { textContent: 'Player' }));
            headRow.appendChild(el('th', { textContent: 'UUID' }));
            headRow.appendChild(el('th', { textContent: 'Registered' }));
            thead.appendChild(headRow);
            table.appendChild(thead);

            var tbody = el('tbody');
            players.forEach(function (p) {
                var name = p.player_name || p.name || '';
                var row = el('tr');
                var nameCell = el('td');
                nameCell.appendChild(makePlayerLink(name));
                row.appendChild(nameCell);
                row.appendChild(el('td', { className: 'uuid-cell', textContent: p.server_uuid || p.uuid || '' }));
                var lastLogin = p.last_login || p.lastLogin || '-';
                row.appendChild(el('td', { textContent: isDateString(lastLogin) ? formatDate(lastLogin) : lastLogin }));
                tbody.appendChild(row);
            });
            table.appendChild(tbody);
            wrapper.appendChild(table);
            card.appendChild(wrapper);
        }).catch(function () {
            card.innerHTML = '';
            card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load players.' }));
        });
    }


    // ══════════════════════════════════════════════════════════════
    //  PLAYER PROFILE PAGE
    // ══════════════════════════════════════════════════════════════

    function loadPlayerProfile(name) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: 'Player Profile' }));

        var layout = el('div', { className: 'profile-layout' });

        var skinDiv = el('div', { className: 'profile-skin' });
        skinDiv.appendChild(el('img', {
            src: 'https://mc-heads.net/body/' + encodeURIComponent(name) + '/200',
            width: '200',
            alt: name + ' skin'
        }));
        layout.appendChild(skinDiv);

        var infoDiv = el('div', { className: 'profile-info' });
        infoDiv.appendChild(el('div', { className: 'profile-name', textContent: name }));
        var uuidEl = el('div', { className: 'profile-uuid', textContent: 'Loading...' });
        infoDiv.appendChild(uuidEl);

        var detailsContainer = el('div', { id: 'profile-details' });
        infoDiv.appendChild(detailsContainer);

        layout.appendChild(infoDiv);
        main.appendChild(layout);

        api('/api/players?offset=0&limit=100').then(function (data) {
            var players = data.players || data;
            var player = null;
            if (players) {
                for (var i = 0; i < players.length; i++) {
                    var pName = players[i].player_name || players[i].name || '';
                    if (pName.toLowerCase() === name.toLowerCase()) {
                        player = players[i];
                        break;
                    }
                }
            }
            if (player) {
                var uuid = player.server_uuid || player.uuid || '';
                uuidEl.textContent = uuid || 'UUID not available';

                if (player.last_login || player.lastLogin) {
                    var loginVal = player.last_login || player.lastLogin;
                    var detail = el('div', { className: 'profile-detail' });
                    detail.appendChild(el('span', { className: 'profile-detail-label', textContent: 'Last Login' }));
                    detail.appendChild(el('span', { className: 'profile-detail-value', textContent: isDateString(loginVal) ? formatDate(loginVal) : loginVal }));
                    detailsContainer.appendChild(detail);
                }

                if (player.first_login || player.firstLogin || player.registered) {
                    var regVal = player.first_login || player.firstLogin || player.registered;
                    var detail2 = el('div', { className: 'profile-detail' });
                    detail2.appendChild(el('span', { className: 'profile-detail-label', textContent: 'Registered' }));
                    detail2.appendChild(el('span', { className: 'profile-detail-value', textContent: isDateString(regVal) ? formatDate(regVal) : regVal }));
                    detailsContainer.appendChild(detail2);
                }
            } else {
                uuidEl.textContent = 'Player not found in database';
            }
        }).catch(function () {
            uuidEl.textContent = 'Could not load player info';
        });

        // Module data sections
        var modulesContainer = el('div', { id: 'profile-modules' });
        main.appendChild(modulesContainer);

        var tryLoadModules = function () {
            if (!modules || modules.length === 0) return;
            modules.forEach(function (mod) {
                var modName = mod.name.toLowerCase();
                api('/api/' + modName + '/player/' + encodeURIComponent(name)).then(function (data) {
                    if (!data) return;
                    var hasData = false;
                    if (Array.isArray(data) && data.length > 0) hasData = true;
                    else if (typeof data === 'object') {
                        for (var k in data) {
                            if (Array.isArray(data[k]) && data[k].length > 0) { hasData = true; break; }
                            else if (!Array.isArray(data[k])) { hasData = true; break; }
                        }
                    }
                    if (!hasData) return;

                    var section = el('div', { className: 'card module-section' });
                    section.appendChild(el('div', { className: 'module-section-title', textContent: mod.name }));
                    renderModuleDataInto(section, data);
                    modulesContainer.appendChild(section);
                }).catch(function () {});
            });
        };

        if (modules.length > 0) {
            tryLoadModules();
        } else {
            api('/api/modules').then(function (mods) {
                modules = mods || [];
                tryLoadModules();
            }).catch(function () {});
        }
    }

    function renderModuleDataInto(container, data) {
        var items = Array.isArray(data) ? data : null;
        if (!items) {
            for (var key in data) {
                if (Array.isArray(data[key])) { items = data[key]; break; }
            }
        }

        if (items && items.length > 0) {
            renderDataTable(container, items);
        } else if (items && items.length === 0) {
            container.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No data.' })]));
        } else {
            var list = el('div', { className: 'kv-list' });
            for (var k in data) {
                var row = el('div', { className: 'kv-row' });
                row.appendChild(el('span', { className: 'kv-key', textContent: capitalize(k) }));
                var val = data[k];
                if (val === null || val === undefined) val = '-';
                row.appendChild(el('span', { className: 'kv-value', textContent: formatCellValue(k, val) }));
                list.appendChild(row);
            }
            container.appendChild(list);
        }
    }


    // ══════════════════════════════════════════════════════════════
    //  GENERIC MODULE PAGE (fallback)
    // ══════════════════════════════════════════════════════════════

    function loadModule(name, path) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: name + ' / ' + capitalize(path) }));

        var card = el('div', { className: 'card' });
        card.innerHTML = '<div class="loading">Loading data...</div>';
        main.appendChild(card);

        // Transactions: load all immediately, with optional player filter
        if (path === 'transactions') {
            card.innerHTML = '';
            var filterBar = el('div', { className: 'filter-bar' });
            filterBar.appendChild(el('label', { textContent: 'Filter by player' }));
            var input = el('input', { type: 'text', placeholder: 'Player name (optional)...' });
            filterBar.appendChild(input);
            var btn = el('button', {
                className: 'btn', textContent: 'Filter',
                onClick: function () {
                    var val = input.value.trim();
                    var url = '/api/' + name.toLowerCase() + '/' + path + '?offset=0&limit=100';
                    if (val) url += '&player=' + encodeURIComponent(val);
                    loadModuleData(card, url);
                }
            });
            filterBar.appendChild(btn);
            var clearBtn = el('button', {
                className: 'btn btn-secondary', textContent: 'Clear',
                onClick: function () {
                    input.value = '';
                    loadModuleData(card, '/api/' + name.toLowerCase() + '/' + path + '?offset=0&limit=100');
                }
            });
            filterBar.appendChild(clearBtn);
            card.appendChild(filterBar);
            var results = el('div', { id: 'module-results' });
            card.appendChild(results);
            input.addEventListener('keydown', function (e) { if (e.key === 'Enter') btn.click(); });
            loadModuleData(card, '/api/' + name.toLowerCase() + '/' + path + '?offset=0&limit=100');
            return;
        }

        loadModuleData(card, '/api/' + name.toLowerCase() + '/' + path);
    }

    function loadModuleData(card, url) {
        var results = card.querySelector('#module-results') || card;
        results.innerHTML = '<div class="loading">Loading data...</div>';

        api(url).then(function (data) {
            results.innerHTML = '';
            var items = Array.isArray(data) ? data : null;
            if (!items) {
                for (var key in data) {
                    if (Array.isArray(data[key])) { items = data[key]; break; }
                }
            }

            if (items && items.length > 0) {
                renderDataTable(results, items);
            } else if (items && items.length === 0) {
                results.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No data found.' })]));
            } else {
                var list = el('div', { className: 'kv-list' });
                for (var k in data) {
                    var row = el('div', { className: 'kv-row' });
                    row.appendChild(el('span', { className: 'kv-key', textContent: capitalize(k) }));
                    row.appendChild(el('span', { className: 'kv-value', textContent: formatCellValue(k, data[k]) }));
                    list.appendChild(row);
                }
                results.appendChild(list);
            }
        }).catch(function () {
            results.innerHTML = '';
            results.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load data.' }));
        });
    }


    // ══════════════════════════════════════════════════════════════
    //  SHARED DATA TABLE RENDERER
    // ══════════════════════════════════════════════════════════════

    function toRoman(num) {
        if (num <= 0 || num > 10) return String(num);
        var vals = [10,9,5,4,1];
        var syms = ['X','IX','V','IV','I'];
        var result = '';
        for (var i = 0; i < vals.length; i++) {
            while (num >= vals[i]) { result += syms[i]; num -= vals[i]; }
        }
        return result;
    }

    function renderItemCell(details) {
        var cell = el('td', { className: 'item-cell' });
        if (!details || !details.material) { cell.textContent = '-'; return cell; }

        var name = details.customName || details.displayMaterial || details.material;
        var isEnchanted = details.enchantments && details.enchantments.length > 0;
        var nameColor = details.customName ? '#55ffff' : (isEnchanted ? '#b48eff' : '#fff');

        var nameEl = el('span', {
            className: 'item-name',
            textContent: name + (details.amount > 1 ? ' x' + details.amount : ''),
            style: 'color: ' + nameColor
        });
        cell.appendChild(nameEl);

        var tooltip = el('div', { className: 'mc-tooltip' });
        tooltip.appendChild(el('div', { className: 'mc-tooltip-name', textContent: name, style: 'color: ' + nameColor }));

        if (details.enchantments) {
            details.enchantments.forEach(function (e) {
                tooltip.appendChild(el('div', { className: 'mc-tooltip-enchant', textContent: e.name + ' ' + toRoman(e.level) }));
            });
        }
        if (details.lore) {
            details.lore.forEach(function (line) {
                if (line) tooltip.appendChild(el('div', { className: 'mc-tooltip-lore', textContent: line }));
            });
        }
        if (details.damage != null && details.maxDurability) {
            var remaining = details.maxDurability - details.damage;
            tooltip.appendChild(el('div', { className: 'mc-tooltip-durability', textContent: 'Durability: ' + remaining + '/' + details.maxDurability }));
        }
        if (details.amount > 1) {
            tooltip.appendChild(el('div', { className: 'mc-tooltip-amount', textContent: 'Amount: ' + details.amount }));
        }

        cell.appendChild(tooltip);
        return cell;
    }

    function renderDataTable(container, items, opts) {
        opts = opts || {};
        var wrapper = el('div', { className: 'table-wrapper' });
        var table = el('table');
        var thead = el('thead');
        var headRow = el('tr');
        var keys = Object.keys(items[0]);

        var hasItemDetails = items[0] && items[0].itemDetails && typeof items[0].itemDetails === 'object';

        var playerColumns = {};
        keys.forEach(function (k) {
            if (isPlayerColumn(k)) playerColumns[k] = true;
        });

        keys = keys.filter(function (k) {
            if (k === 'itemDetails') return false;
            var sample = items[0][k];
            return sample === null || sample === undefined || typeof sample !== 'object';
        });

        if (hasItemDetails) {
            headRow.appendChild(el('th', { textContent: 'Item' }));
        }

        keys.forEach(function (k) {
            headRow.appendChild(el('th', { textContent: capitalize(k) }));
        });

        if (opts.actions) {
            headRow.appendChild(el('th', { textContent: 'Actions' }));
        }

        thead.appendChild(headRow);
        table.appendChild(thead);

        var tbody = el('tbody');
        items.forEach(function (item) {
            var row = el('tr');

            if (hasItemDetails) {
                row.appendChild(renderItemCell(item.itemDetails));
            }

            keys.forEach(function (k) {
                var val = item[k];
                var td = el('td');

                if (playerColumns[k] && val) {
                    td.appendChild(makePlayerLink(String(val)));
                } else {
                    var specialNode = formatCellSpecial(k, val);
                    if (specialNode) {
                        td.appendChild(specialNode);
                    } else {
                        td.textContent = formatCellValue(k, val);
                        if (typeof val === 'number') td.className = 'num-cell';
                        if (k.indexOf('uuid') !== -1 || k.indexOf('UUID') !== -1) td.className = 'uuid-cell';
                    }
                }
                row.appendChild(td);
            });

            if (opts.actions) {
                var actionTd = el('td');
                opts.actions.forEach(function (action) {
                    if (action.condition && !action.condition(item)) return;
                    var btn = el('button', {
                        className: 'btn btn-small ' + (action.cls || ''),
                        textContent: action.label,
                        onClick: function () { action.handler(item); }
                    });
                    actionTd.appendChild(btn);
                });
                row.appendChild(actionTd);
            }

            tbody.appendChild(row);
        });
        table.appendChild(tbody);
        wrapper.appendChild(table);
        container.appendChild(wrapper);
    }


    // ══════════════════════════════════════════════════════════════
    //  DEDICATED MODULE RENDERERS
    // ══════════════════════════════════════════════════════════════

    var moduleRenderers = {};


    // ──────────────────────────────────────────────
    //  XBans Module
    // ──────────────────────────────────────────────

    moduleRenderers['XBans'] = {

        // -- Overview --
        overview: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XBans Overview' }));

            var grid = el('div', { className: 'stats-grid' });
            grid.innerHTML = '<div class="loading">Loading stats...</div>';
            main.appendChild(grid);

            api('/api/xbans/stats').then(function (s) {
                grid.innerHTML = '';
                addStat(grid, 'Active Bans', s.active_bans != null ? s.active_bans : (s.bans != null ? s.bans : '-'), 'red');
                addStat(grid, 'IP Bans', s.active_ip_bans != null ? s.active_ip_bans : (s.ip_bans != null ? s.ip_bans : '-'), 'red');
                addStat(grid, 'Active Mutes', s.active_mutes != null ? s.active_mutes : (s.mutes != null ? s.mutes : '-'), 'orange');
                addStat(grid, 'IP Mutes', s.active_ip_mutes != null ? s.active_ip_mutes : (s.ip_mutes != null ? s.ip_mutes : '-'), 'orange');
                addStat(grid, 'Warns', s.warns != null ? s.warns : (s.total_warns != null ? s.total_warns : '-'), 'yellow');
                addStat(grid, 'Reports', s.reports != null ? s.reports : (s.total_reports != null ? s.total_reports : '-'), 'blue');
                if (s.jailed != null) addStat(grid, 'Jailed', s.jailed, 'purple');
                if (s.watchlist != null) addStat(grid, 'Watchlist', s.watchlist, 'cyan');
            }).catch(function () {
                grid.innerHTML = '';
                grid.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load XBans stats.' }));
            });
        },

        // -- Bans --
        bans: function () {
            renderBanLikePage({
                title: 'XBans - Bans',
                fetchUrl: '/api/xbans/bans',
                actionLabel: 'Unban',
                actionEndpoint: '/api/xbans/unban',
                actionKey: 'player_name',
                actionBody: function (item) { return { player: item.player_name || item.target || item.name }; },
                formTitle: 'Ban Player',
                formEndpoint: '/api/xbans/ban',
                formFields: [
                    { key: 'player', label: 'Player', type: 'text', placeholder: 'Player name', required: true },
                    { key: 'duration', label: 'Duration', type: 'text', placeholder: 'e.g. 7d, 30m, permanent' },
                    { key: 'reason', label: 'Reason', type: 'text', placeholder: 'Reason for ban' }
                ]
            });
        },

        // -- IP Bans --
        'ip-bans': function () {
            renderBanLikePage({
                title: 'XBans - IP Bans',
                fetchUrl: '/api/xbans/ip-bans',
                actionLabel: 'Unban IP',
                actionEndpoint: '/api/xbans/unipban',
                actionKey: 'ip',
                actionBody: function (item) { return { ip: item.ip || item.address, player: item.player_name || item.target }; },
                formTitle: 'IP Ban Player',
                formEndpoint: '/api/xbans/ipban',
                formFields: [
                    { key: 'player', label: 'Player', type: 'text', placeholder: 'Player name', required: true },
                    { key: 'duration', label: 'Duration', type: 'text', placeholder: 'e.g. 7d, 30m, permanent' },
                    { key: 'reason', label: 'Reason', type: 'text', placeholder: 'Reason for IP ban' }
                ]
            });
        },

        // -- Mutes --
        mutes: function () {
            renderBanLikePage({
                title: 'XBans - Mutes',
                fetchUrl: '/api/xbans/mutes',
                actionLabel: 'Unmute',
                actionEndpoint: '/api/xbans/unmute',
                actionKey: 'player_name',
                actionBody: function (item) { return { player: item.player_name || item.target || item.name }; },
                formTitle: 'Mute Player',
                formEndpoint: '/api/xbans/mute',
                formFields: [
                    { key: 'player', label: 'Player', type: 'text', placeholder: 'Player name', required: true },
                    { key: 'duration', label: 'Duration', type: 'text', placeholder: 'e.g. 7d, 30m, permanent' },
                    { key: 'reason', label: 'Reason', type: 'text', placeholder: 'Reason for mute' }
                ]
            });
        },

        // -- IP Mutes --
        'ip-mutes': function () {
            renderBanLikePage({
                title: 'XBans - IP Mutes',
                fetchUrl: '/api/xbans/ip-mutes',
                actionLabel: 'Unmute IP',
                actionEndpoint: '/api/xbans/unipmute',
                actionKey: 'ip',
                actionBody: function (item) { return { ip: item.ip || item.address, player: item.player_name || item.target }; },
                formTitle: 'IP Mute Player',
                formEndpoint: '/api/xbans/ipmute',
                formFields: [
                    { key: 'player', label: 'Player', type: 'text', placeholder: 'Player name', required: true },
                    { key: 'duration', label: 'Duration', type: 'text', placeholder: 'e.g. 7d, 30m, permanent' },
                    { key: 'reason', label: 'Reason', type: 'text', placeholder: 'Reason for IP mute' }
                ]
            });
        },

        // -- Warns --
        warns: function () {
            renderBanLikePage({
                title: 'XBans - Warns',
                fetchUrl: '/api/xbans/warns',
                actionLabel: 'Remove',
                actionEndpoint: '/api/xbans/unwarn',
                actionKey: 'id',
                actionBody: function (item) { return { id: item.id, player: item.player_name || item.target || item.name }; },
                formTitle: 'Warn Player',
                formEndpoint: '/api/xbans/warn',
                formFields: [
                    { key: 'player', label: 'Player', type: 'text', placeholder: 'Player name', required: true },
                    { key: 'reason', label: 'Reason', type: 'text', placeholder: 'Reason for warn' }
                ]
            });
        },

        // -- Reports --
        reports: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XBans - Reports' }));

            var card = el('div', { className: 'card' });
            card.innerHTML = '<div class="loading">Loading reports...</div>';
            main.appendChild(card);

            api('/api/xbans/reports').then(function (data) {
                var items = Array.isArray(data) ? data : (data.reports || data.data || []);
                card.innerHTML = '';
                if (!items || items.length === 0) {
                    card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No reports found.' })]));
                    return;
                }
                renderDataTable(card, items, {
                    actions: [{
                        label: 'Resolve',
                        cls: 'btn-success',
                        handler: function (item) {
                            confirmAction('Resolve this report?', function () {
                                apiPost('/api/xbans/report/resolve', { id: item.id }).then(function () {
                                    showToast('Report resolved.', 'success');
                                    moduleRenderers['XBans'].reports();
                                }).catch(function (err) {
                                    showToast(err.message || 'Failed to resolve report.', 'error');
                                });
                            });
                        }
                    }, {
                        label: 'Delete',
                        cls: 'btn-danger',
                        handler: function (item) {
                            confirmAction('Delete this report? This cannot be undone.', function () {
                                apiPost('/api/xbans/report/delete', { id: item.id }).then(function () {
                                    showToast('Report deleted.', 'success');
                                    moduleRenderers['XBans'].reports();
                                }).catch(function (err) {
                                    showToast(err.message || 'Failed to delete report.', 'error');
                                });
                            });
                        }
                    }]
                });
            }).catch(function () {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load reports.' }));
            });
        },

        // -- Jails --
        jails: function () {
            renderBanLikePage({
                title: 'XBans - Jails',
                fetchUrl: '/api/xbans/jails',
                actionLabel: 'Unjail',
                actionEndpoint: '/api/xbans/unjail',
                actionKey: 'player_name',
                actionBody: function (item) { return { player: item.player_name || item.target || item.name }; },
                formTitle: 'Jail Player',
                formEndpoint: '/api/xbans/jail',
                formFields: [
                    { key: 'player', label: 'Player', type: 'text', placeholder: 'Player name', required: true },
                    { key: 'jail', label: 'Jail Name', type: 'text', placeholder: 'Jail name' },
                    { key: 'duration', label: 'Duration', type: 'text', placeholder: 'e.g. 7d, 30m, permanent' },
                    { key: 'reason', label: 'Reason', type: 'text', placeholder: 'Reason for jail' }
                ]
            });
        },

        // -- Players --
        players: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XBans - Players' }));

            var state = { page: 1, search: '' };
            var resultsContainer = el('div');
            main.appendChild(resultsContainer);

            function loadPage() {
                resultsContainer.innerHTML = '<div class="loading">Loading players...</div>';
                var url = '/api/xbans/players?page=' + state.page + '&limit=50';
                if (state.search) url += '&search=' + encodeURIComponent(state.search);

                api(url).then(function (data) {
                    resultsContainer.innerHTML = '';
                    var items = data.players || data.data || (Array.isArray(data) ? data : []);
                    var total = data.total || data.total_count || items.length;
                    var totalPages = Math.max(1, Math.ceil(total / 50));

                    renderSearchBar(resultsContainer, {
                        placeholder: 'Search players...',
                        value: state.search,
                        onSearch: function (val) {
                            state.search = val;
                            state.page = 1;
                            loadPage();
                        }
                    });

                    if (!items || items.length === 0) {
                        resultsContainer.appendChild(el('div', { className: 'card' }, [
                            el('div', { className: 'empty-state' }, [el('p', { textContent: 'No players found.' })])
                        ]));
                        return;
                    }

                    var card = el('div', { className: 'card' });
                    renderDataTable(card, items);
                    resultsContainer.appendChild(card);

                    if (totalPages > 1) {
                        renderPagination(resultsContainer, state.page, totalPages, function (pg) {
                            state.page = pg;
                            loadPage();
                        });
                    }
                }).catch(function () {
                    resultsContainer.innerHTML = '';
                    resultsContainer.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load players.' }));
                });
            }

            loadPage();
        },

        // -- Watchlist --
        watchlist: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XBans - Watchlist' }));

            // Add form
            var form = el('div', { className: 'action-form' });
            var playerField = el('div', { className: 'form-field' });
            playerField.appendChild(el('label', { textContent: 'Player' }));
            var playerInput = el('input', { type: 'text', placeholder: 'Player name' });
            playerField.appendChild(playerInput);
            form.appendChild(playerField);

            form.appendChild(el('button', {
                className: 'btn btn-success',
                textContent: 'Add to Watchlist',
                onClick: function () {
                    var name = playerInput.value.trim();
                    if (!name) { showToast('Please enter a player name.', 'error'); return; }
                    confirmAction('Add ' + name + ' to the watchlist?', function () {
                        apiPost('/api/xbans/watchlist/add', { player: name }).then(function () {
                            showToast(name + ' added to watchlist.', 'success');
                            playerInput.value = '';
                            loadWatchlist();
                        }).catch(function (err) {
                            showToast(err.message || 'Failed to add to watchlist.', 'error');
                        });
                    });
                }
            }));
            main.appendChild(form);

            var card = el('div', { className: 'card' });
            main.appendChild(card);

            function loadWatchlist() {
                card.innerHTML = '<div class="loading">Loading watchlist...</div>';
                api('/api/xbans/watchlist').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.players || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'Watchlist is empty.' })]));
                        return;
                    }
                    renderDataTable(card, items, {
                        actions: [{
                            label: 'Remove',
                            cls: 'btn-danger',
                            handler: function (item) {
                                var pName = item.player_name || item.player || item.name;
                                confirmAction('Remove ' + pName + ' from watchlist?', function () {
                                    apiPost('/api/xbans/watchlist/remove', { player: pName }).then(function () {
                                        showToast(pName + ' removed from watchlist.', 'success');
                                        loadWatchlist();
                                    }).catch(function (err) {
                                        showToast(err.message || 'Failed to remove from watchlist.', 'error');
                                    });
                                });
                            }
                        }]
                    });
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load watchlist.' }));
                });
            }

            loadWatchlist();
        },

        // -- Audit Log --
        audit: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XBans - Audit Log' }));

            var state = { page: 1, search: '', type: '' };
            var resultsContainer = el('div');
            main.appendChild(resultsContainer);

            function loadPage() {
                resultsContainer.innerHTML = '<div class="loading">Loading audit log...</div>';
                var url = '/api/xbans/audit?page=' + state.page + '&limit=50';
                if (state.search) url += '&search=' + encodeURIComponent(state.search);
                if (state.type) url += '&type=' + encodeURIComponent(state.type);

                api(url).then(function (data) {
                    resultsContainer.innerHTML = '';
                    var items = data.entries || data.data || (Array.isArray(data) ? data : []);
                    var total = data.total || data.total_count || items.length;
                    var totalPages = Math.max(1, Math.ceil(total / 50));

                    renderSearchBar(resultsContainer, {
                        placeholder: 'Search audit log...',
                        value: state.search,
                        filters: [{
                            key: 'type',
                            placeholder: 'All Types',
                            options: [
                                { value: 'BAN', label: 'Ban' },
                                { value: 'UNBAN', label: 'Unban' },
                                { value: 'MUTE', label: 'Mute' },
                                { value: 'UNMUTE', label: 'Unmute' },
                                { value: 'WARN', label: 'Warn' },
                                { value: 'KICK', label: 'Kick' },
                                { value: 'JAIL', label: 'Jail' },
                                { value: 'IPBAN', label: 'IP Ban' },
                                { value: 'IPMUTE', label: 'IP Mute' }
                            ]
                        }],
                        filterValues: { type: state.type },
                        onSearch: function (val, filters) {
                            state.search = val;
                            state.type = filters.type || '';
                            state.page = 1;
                            loadPage();
                        }
                    });

                    if (!items || items.length === 0) {
                        resultsContainer.appendChild(el('div', { className: 'card' }, [
                            el('div', { className: 'empty-state' }, [el('p', { textContent: 'No audit entries found.' })])
                        ]));
                        return;
                    }

                    var card = el('div', { className: 'card' });
                    renderDataTable(card, items);
                    resultsContainer.appendChild(card);

                    if (totalPages > 1) {
                        renderPagination(resultsContainer, state.page, totalPages, function (pg) {
                            state.page = pg;
                            loadPage();
                        });
                    }
                }).catch(function () {
                    resultsContainer.innerHTML = '';
                    resultsContainer.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load audit log.' }));
                });
            }

            loadPage();
        }
    };

    // Shared renderer for ban/mute/warn/jail pages
    function renderBanLikePage(opts) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: opts.title }));

        // Action form
        if (opts.formFields && opts.formEndpoint) {
            var form = el('div', { className: 'action-form' });
            var inputs = {};
            opts.formFields.forEach(function (f) {
                var field = el('div', { className: 'form-field' });
                field.appendChild(el('label', { textContent: f.label }));
                var inp = el('input', { type: f.type || 'text', placeholder: f.placeholder || '' });
                field.appendChild(inp);
                form.appendChild(field);
                inputs[f.key] = inp;
            });

            form.appendChild(el('button', {
                className: 'btn btn-danger',
                textContent: opts.formTitle,
                onClick: function () {
                    var body = {};
                    var valid = true;
                    opts.formFields.forEach(function (f) {
                        var val = inputs[f.key].value.trim();
                        if (f.required && !val) { valid = false; }
                        if (val) body[f.key] = val;
                    });
                    if (!valid) { showToast('Please fill in all required fields.', 'error'); return; }
                    var playerName = body.player || '';
                    confirmAction(opts.formTitle + (playerName ? ' ' + playerName : '') + '?', function () {
                        apiPost(opts.formEndpoint, body).then(function () {
                            showToast('Action completed successfully.', 'success');
                            opts.formFields.forEach(function (f) { inputs[f.key].value = ''; });
                            loadData();
                        }).catch(function (err) {
                            showToast(err.message || 'Action failed.', 'error');
                        });
                    });
                }
            }));
            main.appendChild(form);
        }

        var card = el('div', { className: 'card' });
        main.appendChild(card);

        function loadData() {
            card.innerHTML = '<div class="loading">Loading data...</div>';
            api(opts.fetchUrl).then(function (data) {
                var items = Array.isArray(data) ? data : (data.data || data.bans || data.mutes || data.warns || data.jails || data.entries || []);
                card.innerHTML = '';
                if (!items || items.length === 0) {
                    card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No records found.' })]));
                    return;
                }
                renderDataTable(card, items, {
                    actions: [{
                        label: opts.actionLabel,
                        cls: 'btn-danger',
                        handler: function (item) {
                            var identifier = item[opts.actionKey] || item.player_name || item.target || item.name || item.ip;
                            confirmAction(opts.actionLabel + ' ' + (identifier || '') + '?', function () {
                                var body = opts.actionBody(item);
                                apiPost(opts.actionEndpoint, body).then(function () {
                                    showToast(opts.actionLabel + ' successful.', 'success');
                                    loadData();
                                }).catch(function (err) {
                                    showToast(err.message || opts.actionLabel + ' failed.', 'error');
                                });
                            });
                        }
                    }]
                });
            }).catch(function () {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load data.' }));
            });
        }

        loadData();
    }


    // ──────────────────────────────────────────────
    //  XAuctionHouse Module
    // ──────────────────────────────────────────────

    moduleRenderers['XAuctionHouse'] = {

        // -- Listings --
        listings: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Auction House - Listings' }));

            var card = el('div', { className: 'card' });
            card.innerHTML = '<div class="loading">Loading listings...</div>';
            main.appendChild(card);

            function loadListings() {
                card.innerHTML = '<div class="loading">Loading listings...</div>';
                api('/api/xauctionhouse/listings').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.listings || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No active listings.' })]));
                        return;
                    }
                    renderDataTable(card, items, {
                        actions: [{
                            label: 'Cancel',
                            cls: 'btn-danger',
                            handler: function (item) {
                                confirmAction('Cancel this listing?', function () {
                                    apiPost('/api/xauctionhouse/cancel', { id: item.id }).then(function () {
                                        showToast('Listing cancelled.', 'success');
                                        loadListings();
                                    }).catch(function (err) {
                                        showToast(err.message || 'Failed to cancel listing.', 'error');
                                    });
                                });
                            }
                        }]
                    });
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load listings.' }));
                });
            }

            loadListings();
        },

        // -- Expired --
        expired: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Auction House - Expired' }));

            var actionBar = el('div', { className: 'action-bar' });
            actionBar.appendChild(el('button', {
                className: 'btn btn-danger',
                textContent: 'Clear All Expired',
                onClick: function () {
                    confirmAction('Clear all expired listings? This cannot be undone.', function () {
                        apiPost('/api/xauctionhouse/expired/clear', {}).then(function () {
                            showToast('All expired listings cleared.', 'success');
                            loadExpired();
                        }).catch(function (err) {
                            showToast(err.message || 'Failed to clear expired listings.', 'error');
                        });
                    });
                }
            }));
            main.appendChild(actionBar);

            var card = el('div', { className: 'card' });
            main.appendChild(card);

            function loadExpired() {
                card.innerHTML = '<div class="loading">Loading expired listings...</div>';
                api('/api/xauctionhouse/expired').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.listings || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No expired listings.' })]));
                        return;
                    }
                    renderDataTable(card, items);
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load expired listings.' }));
                });
            }

            loadExpired();
        },

        // -- History --
        history: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Auction House - Sales History' }));

            var state = { page: 1, search: '' };
            var resultsContainer = el('div');
            main.appendChild(resultsContainer);

            function loadPage() {
                resultsContainer.innerHTML = '<div class="loading">Loading history...</div>';
                var url = '/api/xauctionhouse/history?page=' + state.page + '&limit=50';
                if (state.search) url += '&search=' + encodeURIComponent(state.search);

                api(url).then(function (data) {
                    resultsContainer.innerHTML = '';
                    var items = data.sales || data.data || (Array.isArray(data) ? data : []);
                    var total = data.total || data.total_count || items.length;
                    var totalPages = Math.max(1, Math.ceil(total / 50));

                    renderSearchBar(resultsContainer, {
                        placeholder: 'Search by player...',
                        value: state.search,
                        onSearch: function (val) {
                            state.search = val;
                            state.page = 1;
                            loadPage();
                        }
                    });

                    if (!items || items.length === 0) {
                        resultsContainer.appendChild(el('div', { className: 'card' }, [
                            el('div', { className: 'empty-state' }, [el('p', { textContent: 'No sales history found.' })])
                        ]));
                        return;
                    }

                    var card = el('div', { className: 'card' });
                    renderDataTable(card, items);
                    resultsContainer.appendChild(card);

                    if (totalPages > 1) {
                        renderPagination(resultsContainer, state.page, totalPages, function (pg) {
                            state.page = pg;
                            loadPage();
                        });
                    }
                }).catch(function () {
                    resultsContainer.innerHTML = '';
                    resultsContainer.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load sales history.' }));
                });
            }

            loadPage();
        },

        // -- Stats --
        stats: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Auction House - Statistics' }));

            var grid = el('div', { className: 'stats-grid' });
            grid.innerHTML = '<div class="loading">Loading stats...</div>';
            main.appendChild(grid);

            var chartCard = el('div', { className: 'card' });
            chartCard.appendChild(el('h3', { textContent: 'Daily Volume' }));
            main.appendChild(chartCard);

            api('/api/xauctionhouse/stats').then(function (s) {
                grid.innerHTML = '';
                addStat(grid, 'Total Listings', s.total_listings != null ? s.total_listings : '-', '');
                addStat(grid, 'Active Listings', s.active_listings != null ? s.active_listings : '-', 'green');
                addStat(grid, 'Total Sales', s.total_sales != null ? s.total_sales : '-', 'cyan');
                addStat(grid, 'Total Volume', s.total_volume != null ? formatNumber(s.total_volume) : '-', 'purple');
                if (s.expired != null) addStat(grid, 'Expired', s.expired, 'red');
            }).catch(function () {
                grid.innerHTML = '';
                grid.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load stats.' }));
            });

            api('/api/xauctionhouse/stats/daily').then(function (data) {
                var days = Array.isArray(data) ? data : (data.days || data.data || []);
                renderBarChart(chartCard, days, 'date', 'volume');
            }).catch(function () {
                chartCard.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No daily data available.' })]));
            });
        }
    };


    // ──────────────────────────────────────────────
    //  XShops Module
    // ──────────────────────────────────────────────

    moduleRenderers['XShops'] = {

        // -- Shops --
        shops: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Shops - Overview' }));

            var card = el('div', { className: 'card' });
            card.innerHTML = '<div class="loading">Loading shops...</div>';
            main.appendChild(card);

            api('/api/xshops/shops').then(function (data) {
                var items = Array.isArray(data) ? data : (data.shops || data.data || []);
                card.innerHTML = '';
                if (!items || items.length === 0) {
                    card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No shops found.' })]));
                    return;
                }
                renderDataTable(card, items);
            }).catch(function () {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load shops.' }));
            });
        },

        // -- Stock --
        stock: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Shops - Stock' }));

            var actionBar = el('div', { className: 'action-bar' });
            actionBar.appendChild(el('button', {
                className: 'btn btn-success',
                textContent: 'Restock All',
                onClick: function () {
                    confirmAction('Restock all shops to their maximum?', function () {
                        apiPost('/api/xshops/stock/restock', {}).then(function () {
                            showToast('All shops restocked.', 'success');
                            loadStock();
                        }).catch(function (err) {
                            showToast(err.message || 'Failed to restock.', 'error');
                        });
                    });
                }
            }));
            main.appendChild(actionBar);

            var card = el('div', { className: 'card' });
            main.appendChild(card);

            function loadStock() {
                card.innerHTML = '<div class="loading">Loading stock...</div>';
                api('/api/xshops/stock').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.stock || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No stock data.' })]));
                        return;
                    }
                    renderStockTable(card, items);
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load stock.' }));
                });
            }

            function renderStockTable(container, items) {
                var wrapper = el('div', { className: 'table-wrapper' });
                var table = el('table');
                var thead = el('thead');
                var headRow = el('tr');
                var keys = Object.keys(items[0]);
                keys.forEach(function (k) {
                    headRow.appendChild(el('th', { textContent: capitalize(k) }));
                });
                thead.appendChild(headRow);
                table.appendChild(thead);

                var tbody = el('tbody');
                items.forEach(function (item) {
                    var row = el('tr');
                    keys.forEach(function (k) {
                        var td = el('td');
                        var val = item[k];
                        var lowerK = k.toLowerCase();

                        if (lowerK === 'stock' || lowerK === 'quantity' || lowerK === 'amount') {
                            var editSpan = el('span', {
                                className: 'inline-edit',
                                textContent: val != null ? String(val) : '-',
                                onClick: function () {
                                    var currentVal = val != null ? String(val) : '';
                                    var inp = el('input', {
                                        className: 'inline-edit-input',
                                        type: 'number',
                                        value: currentVal
                                    });
                                    editSpan.style.display = 'none';
                                    td.appendChild(inp);
                                    inp.focus();

                                    function save() {
                                        var newVal = inp.value.trim();
                                        if (newVal === '' || newVal === currentVal) {
                                            editSpan.style.display = '';
                                            if (inp.parentNode) inp.parentNode.removeChild(inp);
                                            return;
                                        }
                                        apiPost('/api/xshops/stock/update', {
                                            id: item.id,
                                            shop: item.shop || item.shop_name,
                                            item: item.item || item.item_id || item.material,
                                            stock: parseInt(newVal, 10)
                                        }).then(function () {
                                            showToast('Stock updated.', 'success');
                                            editSpan.textContent = newVal;
                                            editSpan.style.display = '';
                                            if (inp.parentNode) inp.parentNode.removeChild(inp);
                                        }).catch(function (err) {
                                            showToast(err.message || 'Failed to update stock.', 'error');
                                            editSpan.style.display = '';
                                            if (inp.parentNode) inp.parentNode.removeChild(inp);
                                        });
                                    }

                                    inp.addEventListener('blur', save);
                                    inp.addEventListener('keydown', function (e) {
                                        if (e.key === 'Enter') save();
                                        if (e.key === 'Escape') {
                                            editSpan.style.display = '';
                                            if (inp.parentNode) inp.parentNode.removeChild(inp);
                                        }
                                    });
                                }
                            });
                            td.appendChild(editSpan);
                        } else if (isPlayerColumn(k) && val) {
                            td.appendChild(makePlayerLink(String(val)));
                        } else {
                            var specialNode = formatCellSpecial(k, val);
                            if (specialNode) { td.appendChild(specialNode); }
                            else { td.textContent = formatCellValue(k, val); }
                        }
                        row.appendChild(td);
                    });
                    tbody.appendChild(row);
                });
                table.appendChild(tbody);
                wrapper.appendChild(table);
                container.appendChild(wrapper);
            }

            loadStock();
        },

        // -- Prices --
        prices: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Shops - Prices' }));

            var actionBar = el('div', { className: 'action-bar' });
            actionBar.appendChild(el('button', {
                className: 'btn btn-warning',
                textContent: 'Reset All Prices',
                onClick: function () {
                    confirmAction('Reset all prices to default? This cannot be undone.', function () {
                        apiPost('/api/xshops/prices/reset', {}).then(function () {
                            showToast('All prices reset.', 'success');
                            loadPrices();
                        }).catch(function (err) {
                            showToast(err.message || 'Failed to reset prices.', 'error');
                        });
                    });
                }
            }));
            main.appendChild(actionBar);

            var card = el('div', { className: 'card' });
            main.appendChild(card);

            function loadPrices() {
                card.innerHTML = '<div class="loading">Loading prices...</div>';
                api('/api/xshops/prices').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.prices || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No price data.' })]));
                        return;
                    }
                    renderDataTable(card, items);
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load prices.' }));
                });
            }

            loadPrices();
        },

        // -- Transactions --
        transactions: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Shops - Transactions' }));

            var state = { page: 1, search: '', action: '' };
            var resultsContainer = el('div');
            main.appendChild(resultsContainer);

            function loadPage() {
                resultsContainer.innerHTML = '<div class="loading">Loading transactions...</div>';
                var url = '/api/xshops/transactions/search?page=' + state.page + '&limit=50';
                if (state.search) url += '&player=' + encodeURIComponent(state.search);
                if (state.action) url += '&action=' + encodeURIComponent(state.action);

                api(url).then(function (data) {
                    resultsContainer.innerHTML = '';
                    var items = data.transactions || data.data || (Array.isArray(data) ? data : []);
                    var total = data.total || data.total_count || items.length;
                    var totalPages = Math.max(1, Math.ceil(total / 50));

                    renderSearchBar(resultsContainer, {
                        placeholder: 'Search by player...',
                        value: state.search,
                        filters: [{
                            key: 'action',
                            placeholder: 'All Actions',
                            options: [
                                { value: 'BUY', label: 'Buy' },
                                { value: 'SELL', label: 'Sell' }
                            ]
                        }],
                        filterValues: { action: state.action },
                        onSearch: function (val, filters) {
                            state.search = val;
                            state.action = filters.action || '';
                            state.page = 1;
                            loadPage();
                        }
                    });

                    if (!items || items.length === 0) {
                        resultsContainer.appendChild(el('div', { className: 'card' }, [
                            el('div', { className: 'empty-state' }, [el('p', { textContent: 'No transactions found.' })])
                        ]));
                        return;
                    }

                    var card = el('div', { className: 'card' });
                    renderDataTable(card, items);
                    resultsContainer.appendChild(card);

                    if (totalPages > 1) {
                        renderPagination(resultsContainer, state.page, totalPages, function (pg) {
                            state.page = pg;
                            loadPage();
                        });
                    }
                }).catch(function () {
                    resultsContainer.innerHTML = '';
                    resultsContainer.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load transactions.' }));
                });
            }

            loadPage();
        },

        // -- Statistics --
        stats: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'Shops - Statistics' }));

            var grid = el('div', { className: 'stats-grid' });
            grid.innerHTML = '<div class="loading">Loading stats...</div>';
            main.appendChild(grid);

            var chartCard = el('div', { className: 'card' });
            chartCard.appendChild(el('h3', { textContent: 'Daily Sales Volume' }));
            main.appendChild(chartCard);

            api('/api/xshops/stats').then(function (s) {
                grid.innerHTML = '';
                addStat(grid, 'Total Shops', s.total_shops != null ? s.total_shops : '-', '');
                addStat(grid, 'Total Items', s.total_items != null ? s.total_items : '-', 'cyan');
                addStat(grid, 'Transactions Today', s.transactions_today != null ? s.transactions_today : '-', 'green');
                addStat(grid, 'Revenue Today', s.revenue_today != null ? formatNumber(s.revenue_today) : '-', 'purple');
                if (s.total_transactions != null) addStat(grid, 'Total Transactions', formatNumber(s.total_transactions), 'orange');
            }).catch(function () {
                grid.innerHTML = '';
                grid.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load stats.' }));
            });

            api('/api/xshops/stats/daily').then(function (data) {
                var days = Array.isArray(data) ? data : (data.days || data.data || []);
                renderBarChart(chartCard, days, 'date', 'volume');
            }).catch(function () {
                chartCard.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No daily data available.' })]));
            });
        }
    };


    // ──────────────────────────────────────────────
    //  XLogin Module
    // ──────────────────────────────────────────────

    moduleRenderers['XLogin'] = {

        // -- Players --
        players: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XLogin - Players' }));

            var state = { page: 1, search: '' };
            var resultsContainer = el('div');
            main.appendChild(resultsContainer);

            function loadPage() {
                resultsContainer.innerHTML = '<div class="loading">Loading players...</div>';
                var url = '/api/xlogin/players?page=' + state.page + '&limit=50';
                if (state.search) url += '&search=' + encodeURIComponent(state.search);

                api(url).then(function (data) {
                    resultsContainer.innerHTML = '';
                    var items = data.players || data.data || (Array.isArray(data) ? data : []);
                    var total = data.total || data.total_count || items.length;
                    var totalPages = Math.max(1, Math.ceil(total / 50));

                    renderSearchBar(resultsContainer, {
                        placeholder: 'Search players...',
                        value: state.search,
                        onSearch: function (val) {
                            state.search = val;
                            state.page = 1;
                            loadPage();
                        }
                    });

                    if (!items || items.length === 0) {
                        resultsContainer.appendChild(el('div', { className: 'card' }, [
                            el('div', { className: 'empty-state' }, [el('p', { textContent: 'No players found.' })])
                        ]));
                        return;
                    }

                    var card = el('div', { className: 'card' });
                    renderDataTable(card, items, {
                        actions: [
                            {
                                label: 'Reset Password',
                                cls: 'btn-warning',
                                handler: function (item) {
                                    var pName = item.player_name || item.player || item.name || item.username;
                                    confirmAction('Reset password for ' + pName + '?', function () {
                                        apiPost('/api/xlogin/reset-password', { player: pName }).then(function () {
                                            showToast('Password reset for ' + pName + '.', 'success');
                                        }).catch(function (err) {
                                            showToast(err.message || 'Failed to reset password.', 'error');
                                        });
                                    });
                                }
                            },
                            {
                                label: 'Toggle 2FA',
                                cls: '',
                                handler: function (item) {
                                    var pName = item.player_name || item.player || item.name || item.username;
                                    confirmAction('Toggle 2FA for ' + pName + '?', function () {
                                        apiPost('/api/xlogin/toggle-2fa', { player: pName }).then(function () {
                                            showToast('2FA toggled for ' + pName + '.', 'success');
                                            loadPage();
                                        }).catch(function (err) {
                                            showToast(err.message || 'Failed to toggle 2FA.', 'error');
                                        });
                                    });
                                }
                            },
                            {
                                label: 'Delete',
                                cls: 'btn-danger',
                                handler: function (item) {
                                    var pName = item.player_name || item.player || item.name || item.username;
                                    confirmAction('Delete account for ' + pName + '? This cannot be undone.', function () {
                                        apiPost('/api/xlogin/delete', { player: pName }).then(function () {
                                            showToast('Account deleted for ' + pName + '.', 'success');
                                            loadPage();
                                        }).catch(function (err) {
                                            showToast(err.message || 'Failed to delete account.', 'error');
                                        });
                                    });
                                }
                            }
                        ]
                    });
                    resultsContainer.appendChild(card);

                    if (totalPages > 1) {
                        renderPagination(resultsContainer, state.page, totalPages, function (pg) {
                            state.page = pg;
                            loadPage();
                        });
                    }
                }).catch(function () {
                    resultsContainer.innerHTML = '';
                    resultsContainer.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load players.' }));
                });
            }

            loadPage();
        },

        // -- Sessions --
        sessions: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XLogin - Active Sessions' }));

            var card = el('div', { className: 'card' });
            main.appendChild(card);

            function loadSessions() {
                card.innerHTML = '<div class="loading">Loading sessions...</div>';
                api('/api/xlogin/sessions').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.sessions || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No active sessions.' })]));
                        return;
                    }
                    renderDataTable(card, items, {
                        actions: [{
                            label: 'Invalidate',
                            cls: 'btn-danger',
                            handler: function (item) {
                                var pName = item.player_name || item.player || item.name || item.username;
                                confirmAction('Invalidate session for ' + (pName || 'this user') + '?', function () {
                                    apiPost('/api/xlogin/sessions/invalidate', { id: item.id, player: pName }).then(function () {
                                        showToast('Session invalidated.', 'success');
                                        loadSessions();
                                    }).catch(function (err) {
                                        showToast(err.message || 'Failed to invalidate session.', 'error');
                                    });
                                });
                            }
                        }]
                    });
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load sessions.' }));
                });
            }

            loadSessions();
        },

        // -- Statistics --
        stats: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XLogin - Statistics' }));

            var grid = el('div', { className: 'stats-grid' });
            grid.innerHTML = '<div class="loading">Loading stats...</div>';
            main.appendChild(grid);

            api('/api/xlogin/stats').then(function (s) {
                grid.innerHTML = '';
                addStat(grid, 'Registered Players', s.registered != null ? s.registered : (s.total_players != null ? s.total_players : '-'), '');
                addStat(grid, 'Active Sessions', s.active_sessions != null ? s.active_sessions : '-', 'green');
                addStat(grid, '2FA Enabled', s.two_fa_enabled != null ? s.two_fa_enabled : (s.totp_enabled != null ? s.totp_enabled : '-'), 'cyan');
                if (s.logins_today != null) addStat(grid, 'Logins Today', s.logins_today, 'purple');
                if (s.registrations_today != null) addStat(grid, 'Registrations Today', s.registrations_today, 'orange');
            }).catch(function () {
                grid.innerHTML = '';
                grid.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load stats.' }));
            });
        }
    };


    // ──────────────────────────────────────────────
    //  XAutoMessage Module
    // ──────────────────────────────────────────────

    moduleRenderers['XAutoMessage'] = {

        // -- Messages --
        messages: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'AutoMessage - Messages' }));

            var actionBar = el('div', { className: 'action-bar' });
            actionBar.appendChild(el('button', {
                className: 'btn',
                textContent: 'Reload Messages',
                onClick: function () {
                    confirmAction('Reload all messages from config?', function () {
                        apiPost('/api/automessage/reload', {}).then(function () {
                            showToast('Messages reloaded.', 'success');
                            loadMessages();
                        }).catch(function (err) {
                            showToast(err.message || 'Failed to reload.', 'error');
                        });
                    });
                }
            }));
            main.appendChild(actionBar);

            var container = el('div');
            main.appendChild(container);

            function loadMessages() {
                container.innerHTML = '<div class="loading">Loading messages...</div>';
                api('/api/automessage/messages').then(function (data) {
                    container.innerHTML = '';

                    // Data can be an object with type keys or an array
                    var sections = {};
                    if (Array.isArray(data)) {
                        data.forEach(function (msg) {
                            var type = msg.type || 'other';
                            if (!sections[type]) sections[type] = [];
                            sections[type].push(msg);
                        });
                    } else if (typeof data === 'object') {
                        // Could be { bossbars: [...], titles: [...], etc }
                        for (var key in data) {
                            if (Array.isArray(data[key])) {
                                sections[key] = data[key];
                            }
                        }
                        if (Object.keys(sections).length === 0) {
                            // Flat object, wrap it
                            sections['messages'] = [data];
                        }
                    }

                    var hasContent = false;
                    Object.keys(sections).forEach(function (type) {
                        var msgs = sections[type];
                        if (!msgs || msgs.length === 0) return;
                        hasContent = true;

                        var card = el('div', { className: 'card' });
                        card.appendChild(el('h3', { textContent: capitalize(type) }));

                        msgs.forEach(function (msg) {
                            var msgCard = el('div', { className: 'message-card' });

                            var content = el('div', { className: 'message-content' });
                            if (msg.type) {
                                var typeBadge = msg.type.toUpperCase();
                                var badgeCls = 'badge-blue';
                                if (typeBadge === 'BOSSBAR') badgeCls = 'badge-purple';
                                else if (typeBadge === 'TITLE') badgeCls = 'badge-orange';
                                else if (typeBadge === 'ACTIONBAR') badgeCls = 'badge-cyan';
                                else if (typeBadge === 'CHAT') badgeCls = 'badge-green';
                                content.appendChild(el('div', { className: 'message-type' }, [
                                    el('span', { className: 'badge ' + badgeCls, textContent: typeBadge })
                                ]));
                            }
                            var text = msg.message || msg.text || msg.content || JSON.stringify(msg);
                            content.appendChild(el('div', { className: 'message-text', textContent: text }));
                            msgCard.appendChild(content);

                            var actions = el('div', { className: 'message-actions' });
                            var isEnabled = msg.enabled !== false && msg.enabled !== 'false' && msg.enabled !== 0;
                            actions.appendChild(makeToggle(isEnabled, function (checked) {
                                apiPost('/api/automessage/toggle', { id: msg.id, enabled: checked }).then(function () {
                                    showToast('Message ' + (checked ? 'enabled' : 'disabled') + '.', 'success');
                                }).catch(function (err) {
                                    showToast(err.message || 'Failed to toggle message.', 'error');
                                });
                            }));
                            msgCard.appendChild(actions);

                            card.appendChild(msgCard);
                        });

                        container.appendChild(card);
                    });

                    if (!hasContent) {
                        container.appendChild(el('div', { className: 'card' }, [
                            el('div', { className: 'empty-state' }, [el('p', { textContent: 'No messages configured.' })])
                        ]));
                    }
                }).catch(function () {
                    container.innerHTML = '';
                    container.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load messages.' }));
                });
            }

            loadMessages();
        },

        // -- Status --
        status: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'AutoMessage - Status' }));

            var card = el('div', { className: 'card' });
            card.innerHTML = '<div class="loading">Loading status...</div>';
            main.appendChild(card);

            api('/api/automessage/status').then(function (data) {
                card.innerHTML = '';
                var list = el('div', { className: 'kv-list' });
                for (var k in data) {
                    if (typeof data[k] === 'object') continue;
                    var row = el('div', { className: 'kv-row' });
                    row.appendChild(el('span', { className: 'kv-key', textContent: capitalize(k) }));
                    var val = data[k];
                    if (typeof val === 'boolean') {
                        row.appendChild(el('span', { className: 'badge ' + (val ? 'badge-green' : 'badge-red'), textContent: val ? 'Enabled' : 'Disabled' }));
                    } else {
                        row.appendChild(el('span', { className: 'kv-value', textContent: formatCellValue(k, val) }));
                    }
                    list.appendChild(row);
                }
                card.appendChild(list);
            }).catch(function () {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load status.' }));
            });
        }
    };


    // ──────────────────────────────────────────────
    //  XAntiLag Module
    // ──────────────────────────────────────────────

    moduleRenderers['XAntiLag'] = {

        // -- Status --
        status: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XAntiLag - Status' }));

            var grid = el('div', { className: 'stats-grid' });
            grid.innerHTML = '<div class="loading">Loading status...</div>';
            main.appendChild(grid);

            var card = el('div', { className: 'card' });
            card.appendChild(el('h3', { textContent: 'Feature Toggles' }));
            main.appendChild(card);

            api('/api/xantilag/status').then(function (data) {
                grid.innerHTML = '';

                if (data.tps != null) {
                    var tpsVal = typeof data.tps === 'number' ? data.tps.toFixed(1) : data.tps;
                    var tpsColor = 'green';
                    if (typeof data.tps === 'number') {
                        if (data.tps < 15) tpsColor = 'red';
                        else if (data.tps < 18) tpsColor = 'orange';
                    }
                    addStat(grid, 'TPS', tpsVal, tpsColor);
                }
                if (data.entity_count != null) addStat(grid, 'Entities', formatNumber(data.entity_count), '');
                if (data.loaded_chunks != null) addStat(grid, 'Loaded Chunks', formatNumber(data.loaded_chunks), '');
                if (data.memory_used != null) {
                    var memMB = Math.round(data.memory_used / 1024 / 1024);
                    addStat(grid, 'Memory Used', memMB + ' MB', 'orange');
                }

                // Feature toggles as key-value list
                var list = el('div', { className: 'kv-list' });
                var toggleKeys = Object.keys(data).filter(function (k) {
                    return typeof data[k] === 'boolean';
                });

                if (toggleKeys.length > 0) {
                    toggleKeys.forEach(function (k) {
                        var row = el('div', { className: 'kv-row' });
                        row.appendChild(el('span', { className: 'kv-key', textContent: capitalize(k) }));
                        row.appendChild(el('span', { className: 'badge ' + (data[k] ? 'badge-green' : 'badge-red'), textContent: data[k] ? 'Enabled' : 'Disabled' }));
                        list.appendChild(row);
                    });
                    card.appendChild(list);
                } else {
                    card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No feature toggles available.' })]));
                }

                // Also show non-boolean, non-stat fields
                var otherKeys = Object.keys(data).filter(function (k) {
                    return typeof data[k] !== 'boolean' && typeof data[k] !== 'object'
                        && k !== 'tps' && k !== 'entity_count' && k !== 'loaded_chunks'
                        && k !== 'memory_used' && k !== 'memory_max';
                });
                if (otherKeys.length > 0) {
                    var otherCard = el('div', { className: 'card' });
                    otherCard.appendChild(el('h3', { textContent: 'Details' }));
                    var otherList = el('div', { className: 'kv-list' });
                    otherKeys.forEach(function (k) {
                        var row = el('div', { className: 'kv-row' });
                        row.appendChild(el('span', { className: 'kv-key', textContent: capitalize(k) }));
                        row.appendChild(el('span', { className: 'kv-value', textContent: formatCellValue(k, data[k]) }));
                        otherList.appendChild(row);
                    });
                    otherCard.appendChild(otherList);
                    main.appendChild(otherCard);
                }
            }).catch(function () {
                grid.innerHTML = '';
                grid.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load optimizer status.' }));
            });
        },

        // -- Chunks --
        chunks: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: 'XAntiLag - Top Chunks' }));

            var card = el('div', { className: 'card' });
            card.innerHTML = '<div class="loading">Loading chunk data...</div>';
            main.appendChild(card);

            api('/api/xantilag/chunks/top').then(function (data) {
                var items = Array.isArray(data) ? data : (data.chunks || data.data || []);
                card.innerHTML = '';
                if (!items || items.length === 0) {
                    card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No chunk data available.' })]));
                    return;
                }
                renderDataTable(card, items);
            }).catch(function () {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load chunk data.' }));
            });
        }
    };


    // ══════════════════════════════════════════════════════════════
    //  INIT
    // ══════════════════════════════════════════════════════════════

    function init() {
        var token = getToken();
        if (!token) {
            renderLogin();
            return;
        }
        api('/api/auth').then(function () {
            renderDashboard();
        }).catch(function () {
            clearToken();
            renderLogin('Session expired or invalid token. Please reconnect.');
        });
    }

    init();
})();

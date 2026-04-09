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

    // ── Login page ──

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

    // ── Dashboard shell ──

    function renderDashboard() {
        app.innerHTML = '';
        var dash = el('div', { className: 'dashboard' });

        // Sidebar
        var sidebar = el('div', { className: 'sidebar' });

        var header = el('div', { className: 'sidebar-header' });
        header.appendChild(el('h2', { textContent: 'XCore' }));
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
            if (modules.length > 0) {
                var modSection = el('div', { className: 'sidebar-section' });
                modSection.appendChild(el('div', { className: 'sidebar-section-title', textContent: 'Modules' }));
                modules.forEach(function (mod) {
                    (mod.pages || []).forEach(function (page) {
                        var id = 'module:' + mod.name + ':' + page.path;
                        modSection.appendChild(makeLink(page.name || (mod.name + ' - ' + page.path), id));
                    });
                });
                nav.appendChild(modSection);
            }
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

    function navigateTo(pageId) {
        currentPage = pageId;
        setActiveLink(pageId);

        if (pageId === 'overview') loadOverview();
        else if (pageId === 'players') loadPlayers();
        else if (pageId.indexOf('module:') === 0) {
            var parts = pageId.split(':');
            loadModule(parts[1], parts[2]);
        }
    }

    // ── Overview page ──

    function loadOverview() {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: 'Overview' }));

        var grid = el('div', { className: 'stats-grid', id: 'stats-grid' });
        main.appendChild(grid);

        grid.innerHTML = '<div class="loading">Loading stats...</div>';

        api('/api/metrics').then(function (s) {
            grid.innerHTML = '';
            addStat(grid, 'Online Players', s.players_online != null ? s.players_online : '-');
            addStat(grid, 'Modules', s.modules_count != null ? s.modules_count : '-');
            addStat(grid, 'Uptime', formatUptime(s.uptime_seconds ? s.uptime_seconds * 1000 : null));
        }).catch(function (err) {
            grid.innerHTML = '';
            grid.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load stats. Check your token or server connection.' }));
        });
    }

    function addStat(container, label, value) {
        var card = el('div', { className: 'stat-card' });
        card.appendChild(el('div', { className: 'stat-label', textContent: label }));
        card.appendChild(el('div', { className: 'stat-value', textContent: String(value) }));
        container.appendChild(card);
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

    // ── Players page ──

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
            headRow.appendChild(el('th', { textContent: '' }));
            headRow.appendChild(el('th', { textContent: 'Name' }));
            headRow.appendChild(el('th', { textContent: 'UUID' }));
            headRow.appendChild(el('th', { textContent: 'Registered' }));
            thead.appendChild(headRow);
            table.appendChild(thead);

            var tbody = el('tbody');
            players.forEach(function (p) {
                var row = el('tr');
                var uuid = (p.server_uuid || p.uuid || '').replace(/-/g, '');
                var headCell = el('td');
                var headImg = el('img', {
                    src: 'https://mc-heads.net/avatar/' + uuid + '/24',
                    width: '24', height: '24',
                    style: 'border-radius: 4px; vertical-align: middle;'
                });
                headCell.appendChild(headImg);
                row.appendChild(headCell);
                row.appendChild(el('td', { textContent: p.player_name || p.name || '' }));
                row.appendChild(el('td', { className: 'uuid-cell', textContent: p.server_uuid || p.uuid || '' }));
                row.appendChild(el('td', { textContent: p.last_login || p.lastLogin || '-' }));
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

    // ── Module page ──

    function loadModule(name, path) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: name + ' / ' + path }));

        var card = el('div', { className: 'card' });
        card.innerHTML = '<div class="loading">Loading data...</div>';
        main.appendChild(card);

        // Transactions needs a player parameter — show a search form
        if (path === 'transactions') {
            card.innerHTML = '';
            var form = el('div', { className: 'form-group' });
            form.appendChild(el('label', { textContent: 'Player name' }));
            var input = el('input', { type: 'text', placeholder: 'Enter player name...' });
            form.appendChild(input);
            var btn = el('button', {
                className: 'btn', textContent: 'Search',
                onClick: function () {
                    var val = input.value.trim();
                    if (!val) return;
                    loadModuleData(card, '/api/' + name.toLowerCase() + '/' + path + '?player=' + val);
                }
            });
            form.appendChild(btn);
            card.appendChild(form);
            var results = el('div', { id: 'module-results' });
            card.appendChild(results);
            input.addEventListener('keydown', function (e) { if (e.key === 'Enter') btn.click(); });
            input.focus();
            return;
        }

        loadModuleData(card, '/api/' + name.toLowerCase() + '/' + path);
    }

    function loadModuleData(card, url) {
        var results = card.querySelector('#module-results') || card;
        results.innerHTML = '<div class="loading">Loading data...</div>';

        api(url).then(function (data) {
            results.innerHTML = '';
            // Try to render as table if data is an array or has an array property
            var items = Array.isArray(data) ? data : null;
            if (!items) {
                for (var key in data) {
                    if (Array.isArray(data[key])) { items = data[key]; break; }
                }
            }

            if (items && items.length > 0) {
                var wrapper = el('div', { className: 'table-wrapper' });
                var table = el('table');
                var thead = el('thead');
                var headRow = el('tr');
                var keys = Object.keys(items[0]);
                keys.forEach(function (k) {
                    headRow.appendChild(el('th', { textContent: k.replace(/_/g, ' ') }));
                });
                thead.appendChild(headRow);
                table.appendChild(thead);

                var tbody = el('tbody');
                items.forEach(function (item) {
                    var row = el('tr');
                    keys.forEach(function (k) {
                        var val = item[k];
                        if (val === null || val === undefined) val = '-';
                        row.appendChild(el('td', { textContent: String(val) }));
                    });
                    tbody.appendChild(row);
                });
                table.appendChild(tbody);
                wrapper.appendChild(table);
                results.appendChild(wrapper);
            } else if (items && items.length === 0) {
                results.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: 'No data found.' })]));
            } else {
                // Render as key-value pairs
                var list = el('div', { className: 'kv-list' });
                for (var k in data) {
                    var row = el('div', { className: 'kv-row' });
                    row.appendChild(el('span', { className: 'kv-key', textContent: k.replace(/_/g, ' ') }));
                    row.appendChild(el('span', { className: 'kv-value', textContent: String(data[k]) }));
                    list.appendChild(row);
                }
                results.appendChild(list);
            }
        }).catch(function () {
            results.innerHTML = '';
            results.appendChild(el('div', { className: 'error-msg', textContent: 'Failed to load data.' }));
        });
    }

    // ── Init ──

    function init() {
        var token = getToken();
        if (!token) {
            renderLogin();
            return;
        }
        // Validate token
        api('/api/auth').then(function () {
            renderDashboard();
        }).catch(function () {
            clearToken();
            renderLogin('Session expired or invalid token. Please reconnect.');
        });
    }

    init();
})();

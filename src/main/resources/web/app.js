(function () {
    'use strict';

    var API = window.location.origin;
    var app = document.getElementById('app');
    var currentPage = null;
    var modules = [];

    // ── Translations ──
    //
    // Served by XCore at /api/lang in the language chosen in config.yml, so the dashboard follows
    // the same single setting as the game messages. Public on purpose: the login screen needs its
    // labels before there is any token to authenticate with.
    var strings = {};

    /**
     * Looks up a UI string. Falls back to the key itself, which keeps the interface usable (and
     * the missing key visible) if a translation file is incomplete.
     */
    /**
     * Translates a key, falling back to the key itself so an untranslated string still reads.
     * Optional replacements substitute both {name} and %name%, as the Java side does.
     */
    function t(key, replacements) {
        var v = strings[key];
        if (v === undefined || v === null) v = key;
        if (replacements) {
            for (var name in replacements) {
                var value = replacements[name] === undefined || replacements[name] === null
                    ? '' : String(replacements[name]);
                v = v.split('{' + name + '}').join(value).split('%' + name + '%').join(value);
            }
        }
        return v;
    }

    function loadStrings() {
        return fetch(API + '/api/lang')
            .then(function (res) { return res.ok ? res.json() : {}; })
            .then(function (data) { strings = data || {}; })
            .catch(function () { strings = {}; });
    }

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

    /**
     * The label for a raw JSON field.
     *
     * <p>Module payloads are keyed for machines — <code>player_uuid</code>, <code>warn_count</code>,
     * <code>listed_at</code> — and the dashboard used to print those keys prettified, which is how a
     * French page ended up reading "Player Uuid" and "Listed At". A module names its fields in its
     * own web language file under <code>field-&lt;key&gt;</code>; anything it has not named falls
     * back to the prettified key, so a new field is never blank, just untranslated.</p>
     */
    function fieldLabel(key) {
        var named = strings['field-' + key];
        if (named) return named;
        // Modules key their payloads either way — player_uuid here, itemUuid there — and a label
        // named once should serve both rather than being declared twice per module.
        var normalized = normalizeKey(key);
        named = strings['field-' + normalized];
        if (named) return named;
        var direct = strings[key] || strings[normalized];
        if (direct) return direct;
        return capitalize(normalized);
    }

    /** camelCase and snake_case reduced to one form, so a lookup can be written once. */
    function normalizeKey(key) {
        return String(key).replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
    }

    /** Whether a field holds a moment in time, so a bare number can be read as one. */
    function looksTemporal(key) {
        return /(^|_)(at|date|time|since|expires|expiration|seen|login|created|registered|until)($|_)/i
            .test(normalizeKey(key));
    }

    function capitalize(str) {
        // camelCase is split too: an unnamed field should read "Item Uuid", not "ItemUuid".
        return String(str).replace(/([a-z0-9])([A-Z])/g, '$1 $2')
            .replace(/_/g, ' ').replace(/\b\w/g, function (c) { return c.toUpperCase(); });
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
        if (typeof val === 'number') {
            // A timestamp printed as a quantity reads as "1 786 653 860 533", which is how the
            // XLogin session list showed the moment it was created.
            if (looksTemporal(key)) {
                if (val > 100000000000) return formatDateTime(val);
                if (val > 1000000000) return formatDateTime(val * 1000);
            }
            return formatNumber(val);
        }
        if (isDateString(String(val))) return formatDate(String(val));
        // Some values are identifiers the server chose, not words a reader should have to know:
        // a module names them under value-<identifier> exactly as it names its fields.
        var named = strings['value-' + String(val)];
        if (named) return named;
        return String(val);
    }

    /**
     * A field rendered as a node rather than text, so a state can look like one.
     *
     * <p>A column of "true" and "false" is a table nobody reads: what matters is whether the player
     * is banned, and that should be visible without parsing English words.</p>
     */
    function valueNode(key, val, compact) {
        if (typeof val === 'boolean') {
            var word = t(val ? 'label-yes' : 'label-no');
            // In a table the column heading already names the field, so the mark alone carries it;
            // in a list, where a line can be read on its own, the word earns its place.
            return el('span', {
                className: 'pill ' + (val ? 'pill-on' : 'pill-off'),
                textContent: compact ? (val ? '\u2713' : '\u2715') : (val ? '\u2713 ' : '\u2715 ') + word,
                title: word
            });
        }
        return document.createTextNode(formatCellValue(key, val));
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
        card.appendChild(el('h3', { textContent: t('confirm-action') }));
        card.appendChild(el('p', { textContent: message }));

        var actions = el('div', { className: 'modal-actions' });
        actions.appendChild(el('button', {
            className: 'btn btn-secondary',
            textContent: t('cancel'),
            onClick: function () { overlay.classList.add('hidden'); }
        }));
        actions.appendChild(el('button', {
            className: 'btn btn-danger',
            textContent: t('confirm'),
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
            textContent: t('previous'),
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
            textContent: t('next'),
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
        'FIXED_PRICE': { cls: 'badge-green', label: t('buy-now') },
        'AUCTION': { cls: 'badge-purple', label: t('auction') },
        'BUY_NOW': { cls: 'badge-green', label: t('buy-now') }
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
                return el('span', { className: 'badge badge-green', textContent: t('active') });
            } else if (sVal === 'false' || sVal === '0' || sVal === 'expired' || sVal === 'no' || sVal === 'inactive') {
                return el('span', { className: 'badge badge-red', textContent: t('expired') });
            }
        }

        // Expiration column -> "Permanent" if empty/null/none
        if (lowerKey === 'expiration' || lowerKey === 'expires' || lowerKey === 'expire_date'
            || lowerKey === 'end_date' || lowerKey === 'duration') {
            if (isPermanent(val)) {
                return el('span', { className: 'badge badge-red', textContent: t('permanent') });
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
                return el('span', { className: 'badge badge-blue', textContent: t('global') });
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
            container.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-chart-data-available') })]));
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
            var value = d[valueKey];
            var tooltip = typeof value === 'number' && value % 1 !== 0 ? value.toFixed(2) : formatNumber(value);
            bar.appendChild(el('span', { className: 'bar-tooltip', textContent: tooltip }));
            col.appendChild(bar);
            var raw = d[labelKey];
            var labelText;
            if (typeof raw === 'number' && raw > 1e11) {
                // An epoch timestamp: the hour is what a reader can place, the millis are noise.
                var when = new Date(raw);
                labelText = ('0' + when.getHours()).slice(-2) + ':' + ('0' + when.getMinutes()).slice(-2);
            } else {
                labelText = String(raw || '');
                if (labelText.length > 10) labelText = labelText.substring(5);
            }
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
            textContent: t('search'),
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

        card.appendChild(el('h1', { textContent: t('xcore-dashboard') }));
        card.appendChild(el('p', { textContent: t('login-hint') }));

        var form = el('div', { className: 'form-group' });
        form.appendChild(el('label', { textContent: t('api-token'), for: 'token-input' }));
        var input = el('input', { type: 'text', id: 'token-input', placeholder: t('paste-your-token-here') });
        form.appendChild(input);
        card.appendChild(form);

        var errEl = el('div', { className: 'login-error', id: 'login-error' });
        if (errorMsg) {
            errEl.textContent = errorMsg;
            errEl.style.display = 'block';
        }

        var btn = el('button', {
            className: 'btn',
            textContent: t('connect'),
            onClick: function () {
                var val = input.value.trim();
                if (!val) {
                    errEl.textContent = t('please-enter-a-token');
                    errEl.style.display = 'block';
                    return;
                }
                setToken(val);
                api('/api/auth').then(function () {
                    renderDashboard();
                }).catch(function () {
                    clearToken();
                    errEl.textContent = t('invalid-token-or-server-unreachable');
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
        loadMinecraftLang();
        app.innerHTML = '';
        var dash = el('div', { className: 'dashboard' });

        // Sidebar
        var sidebar = el('div', { className: 'sidebar' });

        var header = el('div', { className: 'sidebar-header' });
        header.appendChild(el('h2', { textContent: t('xcore') }));
        // The badge said "Server" on every server there is. The name is one call away and is what
        // tells an admin with three tabs open which one they are looking at.
        var serverBadge = el('span', { className: 'sidebar-badge', textContent: t('server') });
        header.appendChild(serverBadge);
        api('/api/metrics').then(function (m) {
            if (m && m.server_name) serverBadge.textContent = m.server_name;
        }).catch(function () {});
        sidebar.appendChild(header);

        var nav = el('div', { className: 'sidebar-nav', id: 'sidebar-nav' });
        sidebar.appendChild(nav);

        var footer = el('div', { className: 'sidebar-footer' });
        footer.appendChild(el('button', {
            className: 'btn-logout',
            textContent: t('logout'),
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

    /** Which module groups the sidebar is showing expanded, remembered across reloads. */
    function expandedGroups() {
        try { return JSON.parse(localStorage.getItem('xcore_open_groups') || '{}'); }
        catch (e) { return {}; }
    }
    function setGroupExpanded(name, open) {
        var state = expandedGroups();
        if (open) state[name] = 1; else delete state[name];
        localStorage.setItem('xcore_open_groups', JSON.stringify(state));
    }

    function buildSidebar() {
        var nav = document.getElementById('sidebar-nav');
        nav.innerHTML = '';

        // Core section
        var coreSection = el('div', { className: 'sidebar-section' });
        coreSection.appendChild(el('div', { className: 'sidebar-section-title', textContent: t('core') }));
        coreSection.appendChild(makeLink(t('overview'), 'overview'));
        coreSection.appendChild(makeLink(t('players'), 'players'));
        nav.appendChild(coreSection);

        api('/api/modules').then(function (mods) {
            modules = mods || [];
            if (modules.length === 0) return;

            var section = el('div', { className: 'sidebar-section' });
            section.appendChild(el('div', { className: 'sidebar-section-title', textContent: t('addons') }));
            var open = expandedGroups();

            modules.forEach(function (mod) {
                if (!mod.pages || mod.pages.length === 0) return;
                section.appendChild(makeModuleGroup(mod, !!open[mod.name]));
            });
            nav.appendChild(section);
        }).catch(function () {
            // Modules endpoint may not be available
        });
    }

    /**
     * One collapsible module in the sidebar.
     *
     * The header is a link in its own right — it opens the module's home page — and the chevron
     * folds its pages away, so a dozen addons no longer means fifty tabs down the side.
     */
    function makeModuleGroup(mod, startOpen) {
        var group = el('div', { className: 'sidebar-group' });
        var homeId = 'module:' + mod.name;

        var header = el('a', {
            className: 'sidebar-link sidebar-group-header' + (currentPage === homeId ? ' active' : ''),
            onClick: function (e) {
                e.preventDefault();
                if (!group.classList.contains('open')) toggle(true);
                navigateTo(homeId);
            }
        });
        header.dataset.page = homeId;

        var caret = el('span', {
            className: 'sidebar-caret',
            textContent: '▸',
            onClick: function (e) {
                // The chevron folds without navigating; the rest of the header does both.
                e.preventDefault();
                e.stopPropagation();
                toggle(!group.classList.contains('open'));
            }
        });
        header.appendChild(caret);
        header.appendChild(el('span', { className: 'sidebar-group-name', textContent: mod.name }));
        header.appendChild(el('span', { className: 'sidebar-group-count', textContent: String(mod.pages.length) }));
        group.appendChild(header);

        var pages = el('div', { className: 'sidebar-group-pages' });
        mod.pages.forEach(function (page) {
            pages.appendChild(makeLink(t(page.name || page.path), 'module:' + mod.name + ':' + page.path));
        });
        group.appendChild(pages);

        function toggle(open) {
            group.classList.toggle('open', open);
            caret.textContent = open ? '▾' : '▸';
            setGroupExpanded(mod.name, open);
        }
        toggle(startOpen || (currentPage.indexOf('module:' + mod.name) === 0));
        return group;
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
        // Landing on a page from a link elsewhere should unfold the group it belongs to.
        if (pageId.indexOf('module:') === 0) {
            var name = pageId.split(':')[1];
            var header = document.querySelector('.sidebar-group-header[data-page="module:' + name + '"]');
            var group = header && header.parentNode;
            if (group && !group.classList.contains('open')) {
                group.classList.add('open');
                var caret = group.querySelector('.sidebar-caret');
                if (caret) caret.textContent = '▾';
                setGroupExpanded(name, true);
            }
        }
    }


    // ══════════════════════════════════════════════════════════════
    //  ROUTING
    // ══════════════════════════════════════════════════════════════

    function navigateTo(pageId) {
        // Leaving the overview stops its refresh; without this, every visit stacked another timer.
        stopOverviewRefresh();
        currentPage = pageId;
        setActiveLink(pageId);

        if (pageId === 'overview') { loadOverview(); return; }
        if (pageId === 'players') { loadPlayers(); return; }
        if (pageId.indexOf('player:') === 0) { loadPlayerProfile(pageId.split(':')[1]); return; }

        if (pageId.indexOf('module:') === 0) {
            var parts = pageId.split(':');
            var modName = parts[1];
            var pagePath = parts[2] || '';

            // No page means the module's own home.
            if (!pagePath) { renderModuleHome(modName); return; }

            // A page that describes itself wins: it is the module's own definition, served by
            // the server, and it needs nothing about this addon to live in here.
            var spec = findSpec(modName, pagePath);
            if (spec) { renderSpecPage(modName, pagePath, spec); return; }

            // Modules that have not been migrated yet keep their bundled renderer.
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

    /** The installation at a glance: versions, storage, and what is switched on. */
    function renderSystemCard(card, s) {
        var rows = [
            ['xcore-version', s.xcore_version],
            ['server-software', s.server_software],
            ['java-version', s.java_version],
            ['database', s.database],
            ['worlds', s.worlds],
            ['cache-entries', s.cache_size != null ? formatNumber(s.cache_size) : null],
            ['redis', s.redis == null ? null : t(s.redis ? 'status-enabled' : 'status-disabled')],
            ['cross-server', s.sync_running == null ? null : t(s.sync_running ? 'status-enabled' : 'status-disabled')],
            ['dashboard-sessions', s.sessions]
        ].filter(function (row) { return row[1] !== null && row[1] !== undefined; });

        if (rows.length === 0) { card.remove(); return; }

        card.innerHTML = '';
        card.appendChild(el('h3', { textContent: t('system') }));
        var list = el('div', { className: 'kv-list' });
        rows.forEach(function (row) {
            var line = el('div', { className: 'kv-row' });
            line.appendChild(el('span', { className: 'kv-key', textContent: t(row[0]) }));
            line.appendChild(el('span', { className: 'kv-value', textContent: String(row[1]) }));
            list.appendChild(line);
        });
        card.appendChild(list);
    }

    /** A card per installed addon, so the home page is a way in and not just a status board. */
    function renderModulesCard(main) {
        var card = el('div', { className: 'card' });
        card.appendChild(el('h3', { textContent: t('addons') }));
        main.appendChild(card);

        function fill(mods) {
            if (!mods || mods.length === 0) {
                card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-addon-installed') })]));
                return;
            }
            var grid = el('div', { className: 'page-grid' });
            mods.forEach(function (mod) {
                var tile = el('a', {
                    className: 'page-tile',
                    onClick: function (e) { e.preventDefault(); navigateTo('module:' + mod.name); }
                });
                tile.appendChild(el('span', { className: 'page-tile-name', textContent: mod.name }));
                tile.appendChild(el('span', {
                    className: 'page-tile-kind',
                    textContent: (mod.pages ? mod.pages.length : 0) + ' ' + t('pages').toLowerCase()
                }));
                grid.appendChild(tile);
            });
            card.appendChild(grid);
        }

        if (modules.length > 0) fill(modules);
        else api('/api/modules').then(function (mods) { modules = mods || []; fill(modules); }).catch(function () { card.remove(); });
    }

    /** Handle of the overview's own refresh, so leaving the page stops it. */
    var overviewTimer = null;

    function stopOverviewRefresh() {
        if (overviewTimer) { clearInterval(overviewTimer); overviewTimer = null; }
    }

    function loadOverview() {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: t('overview') }));

        var grid = el('div', { className: 'stats-grid', id: 'stats-grid' });
        main.appendChild(grid);
        grid.innerHTML = '<div class="loading">' + t('loading') + '</div>';

        var recentCard = el('div', { className: 'card', id: 'recent-players-card' });
        recentCard.appendChild(el('h3', { textContent: t('recent-players') }));
        recentCard.appendChild(el('div', { className: 'loading', textContent: t('loading') }));
        main.appendChild(recentCard);

        var detailsCard = el('div', { className: 'card', id: 'system-card' });
        main.insertBefore(detailsCard, recentCard);

        refreshMetrics();

        function refreshMetrics() {
        api('/api/metrics').then(function (s) {
            grid.innerHTML = '';

            if (s.server_name) addStat(grid, t('server'), s.server_name, '');

            var players = s.players_online != null ? s.players_online : '-';
            if (s.players_max) players += ' / ' + s.players_max;
            addStat(grid, t('players'), players, 'green');

            if (s.tps != null) {
                var tps = typeof s.tps === 'number' ? s.tps.toFixed(1) : s.tps;
                var tpsColor = 'green';
                if (typeof s.tps === 'number') {
                    if (s.tps < 15) tpsColor = 'red';
                    else if (s.tps < 18) tpsColor = 'orange';
                }
                addStat(grid, t('tps'), tps, tpsColor);
            }

            addStat(grid, t('uptime'), formatUptime(s.uptime_seconds ? s.uptime_seconds * 1000 : null), 'cyan');

            if (s.memory_used != null && s.memory_max != null) {
                var used = Math.round(s.memory_used / 1024 / 1024);
                var max = Math.round(s.memory_max / 1024 / 1024);
                var pct = Math.round((s.memory_used / s.memory_max) * 100);
                addStat(grid, t('memory'), used + ' / ' + max + ' MB', pct > 85 ? 'red' : pct > 65 ? 'orange' : 'green');
            }

            if (s.addons_total != null) {
                addStat(grid, t('addons'), s.addons_enabled + ' / ' + s.addons_total, 'purple');
            } else {
                addStat(grid, t('modules'), s.modules_count != null ? s.modules_count : '-', 'purple');
            }

            if (s.cache_hit_rate != null) {
                addStat(grid, t('cache-hit-rate'), s.cache_hit_rate + '%',
                    s.cache_hit_rate > 80 ? 'green' : s.cache_hit_rate > 50 ? 'orange' : 'red');
            }

            renderSystemCard(detailsCard, s);
        }).catch(function () {
            grid.innerHTML = '';
            // Emptied rather than removed: the next refresh needs somewhere to write.
            detailsCard.innerHTML = '';
            grid.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-stats-check-your-token-or-server') }));
        });
        }

        renderModulesCard(main);

        // Load recent players
        loadRecentPlayers();

        // A dashboard nobody reloads shows a server as it was when the tab was opened. The figures
        // that move — players, TPS, memory — are re-read every five seconds; the module list and
        // the page skeleton are not, so nothing flickers and the scroll position holds.
        stopOverviewRefresh();
        overviewTimer = setInterval(function () {
            if (currentPage !== 'overview') { stopOverviewRefresh(); return; }
            if (document.hidden) return;  // nothing to show a tab nobody is looking at
            refreshMetrics();
            loadRecentPlayers();
        }, 5000);
    }

    function loadRecentPlayers() {
        api('/api/players?offset=0&limit=5').then(function (data) {
            var players = data.players || data;
            var rCard = document.getElementById('recent-players-card');
            if (!rCard) return;
            rCard.innerHTML = '';
            rCard.appendChild(el('h3', { textContent: t('recent-players') }));

            if (!players || players.length === 0) {
                rCard.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-players-found') })]));
                return;
            }

            var wrapper = el('div', { className: 'table-wrapper' });
            var table = el('table');
            var thead = el('thead');
            var headRow = el('tr');
            headRow.appendChild(el('th', { textContent: t('player') }));
            headRow.appendChild(el('th', { textContent: t('last-seen') }));
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
                rCard.appendChild(el('h3', { textContent: t('recent-players') }));
                rCard.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('could-not-load-recent-players') })]));
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
        main.appendChild(el('h1', { className: 'page-title', textContent: t('players') }));

        var card = el('div', { className: 'card' });
        card.innerHTML = '<div class="loading">' + t('loading') + '</div>';
        main.appendChild(card);

        api('/api/players?offset=0&limit=100').then(function (data) {
            var players = data.players || data;
            if (!players || players.length === 0) {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-players-found') })]));
                return;
            }

            card.innerHTML = '';
            var wrapper = el('div', { className: 'table-wrapper' });
            var table = el('table');
            var thead = el('thead');
            var headRow = el('tr');
            headRow.appendChild(el('th', { textContent: t('player') }));
            headRow.appendChild(el('th', { textContent: t('uuid') }));
            headRow.appendChild(el('th', { textContent: t('registered') }));
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
            card.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-players') }));
        });
    }


    // ══════════════════════════════════════════════════════════════
    //  PLAYER PROFILE PAGE
    // ══════════════════════════════════════════════════════════════

    function loadPlayerProfile(name) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: t('player-profile') }));

        // ── Banner: the player's card, not a row in a table ──
        var banner = el('div', { className: 'profile-banner' });

        // A flat face rather than the isometric head: the 3D render is transparent around the
        // cube, so the medallion's own background showed through as a black frame. The face fills
        // the square edge to edge.
        var avatar = el('div', { className: 'profile-avatar' });
        avatar.appendChild(el('img', {
            src: 'https://mc-heads.net/avatar/' + encodeURIComponent(name) + '/128',
            alt: name
        }));
        banner.appendChild(avatar);

        var headline = el('div', { className: 'profile-headline' });
        headline.appendChild(el('div', { className: 'profile-name', textContent: name }));
        var ids = el('div', { className: 'profile-ids' });
        headline.appendChild(ids);
        banner.appendChild(headline);

        // Status and dates share a column on the right. Under the banner the dates never found a
        // good place: the medallion hangs into that space and pushed them around.
        var side = el('div', { className: 'profile-side' });
        var badges = el('div', { className: 'profile-badges' });
        side.appendChild(badges);
        var meta = el('div', { className: 'profile-meta' });
        side.appendChild(meta);
        banner.appendChild(side);
        main.appendChild(banner);

        /** One identifier line, click-to-copy — UUIDs exist to be pasted elsewhere. */
        function addId(labelKey, value) {
            if (!value) return;
            var row = el('div', { className: 'profile-id' });
            row.appendChild(el('span', { className: 'profile-id-label', textContent: t(labelKey) }));
            row.appendChild(el('span', {
                className: 'profile-id-value',
                textContent: value,
                title: t('click-to-copy'),
                onClick: function () {
                    if (navigator.clipboard) navigator.clipboard.writeText(value);
                    showToast(t('copied'), 'success');
                }
            }));
            ids.appendChild(row);
        }

        function addFact(label, value) {
            if (value === null || value === undefined || value === '') return;
            var box = el('div', { className: 'profile-meta-item' });
            box.appendChild(el('span', { className: 'profile-meta-label', textContent: label }));
            box.appendChild(el('span', { className: 'profile-meta-value', textContent: value }));
            meta.appendChild(box);
        }

        // The endpoint searches by name, so a player outside the first page is still found —
        // the profile used to scan the 100 most recent and give up.
        api('/api/players?search=' + encodeURIComponent(name) + '&limit=25').then(function (data) {
            var players = data.players || data || [];
            var player = null;
            for (var i = 0; i < players.length; i++) {
                var candidate = players[i].player_name || players[i].name || '';
                if (candidate.toLowerCase() === name.toLowerCase()) { player = players[i]; break; }
            }
            if (!player) {
                ids.appendChild(el('div', { className: 'profile-id' },
                    [el('span', { className: 'profile-id-value', textContent: t('player-not-in-database') })]));
                return;
            }

            var badge = el('span', { className: 'badge', textContent: t('offline') });
            badges.appendChild(badge);
            api('/api/metrics').then(function (m) {
                var online = Array.isArray(m.online_players)
                    && m.online_players.indexOf(player.player_name) !== -1;
                badge.className = 'badge ' + (online ? 'badge-green' : '');
                badge.textContent = online ? t('online') : t('offline');
            }).catch(function () {});

            addId('server-uuid', player.server_uuid || player.uuid);
            addId('mojang-uuid', player.mojang_uuid);

            var last = player.last_login || player.lastLogin;
            addFact(t('last-login'), last ? (isDateString(last) ? formatDate(last) : last) : t('never'));
            var reg = player.registered || player.first_login || player.created_at;
            addFact(t('registered'), reg ? (isDateString(reg) ? formatDate(reg) : reg) : null);
        }).catch(function () {
            ids.appendChild(el('div', { className: 'profile-id' },
                [el('span', { className: 'profile-id-value', textContent: t('failed-to-load-data') })]));
        });

        // ── What each module knows about them ──
        var modulesContainer = el('div', { id: 'profile-modules' });
        main.appendChild(modulesContainer);

        function loadModuleSections() {
            modules.forEach(function (mod) {
                api('/api/' + mod.name.toLowerCase() + '/player/' + encodeURIComponent(name)).then(function (data) {
                    if (!data || !hasContent(data)) return;
                    var section = el('div', { className: 'card module-section' });
                    section.appendChild(el('h3', { textContent: mod.name }));
                    renderModuleDataInto(section, data);
                    modulesContainer.appendChild(section);
                }).catch(function () {});
            });
        }

        function hasContent(data) {
            if (Array.isArray(data)) return data.length > 0;
            if (typeof data !== 'object') return false;
            for (var k in data) {
                if (Array.isArray(data[k])) { if (data[k].length > 0) return true; }
                else if (data[k] !== null && data[k] !== '') return true;
            }
            return false;
        }

        if (modules.length > 0) loadModuleSections();
        else api('/api/modules').then(function (mods) { modules = mods || []; loadModuleSections(); }).catch(function () {});
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
            container.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-data') })]));
        } else {
            renderProfileFacts(container, data);
        }
    }

    /** Symbol for a state, so a sanction is recognisable before it is read. */
    var FLAG_SYMBOLS = {
        banned: '\u26D4', ip_banned: '\u26D4', muted: '\uD83D\uDD07', ip_muted: '\uD83D\uDD07',
        warned: '\u26A0', reported: '\uD83D\uDEA9', jailed: '\uD83D\uDD12', frozen: '\u2744',
        watched: '\uD83D\uDC41', premium: '\u2605', two_factor_enabled: '\uD83D\uDD11'
    };

    /**
     * One module's view of a player.
     *
     * <p>A flat table of thirty rows, half of them reading "false", hides the one line that matters.
     * The payload is split by what each field is worth looking at: states that are <em>on</em> come
     * first as badges, tallies that are not zero become figures, and the descriptive fields follow
     * as a list. Everything else — a state that is off, a counter at zero — is left out: its absence
     * is the information.</p>
     */
    function renderProfileFacts(container, data) {
        var flags = [], counters = [], rest = [];
        for (var k in data) {
            var v = data[k];
            if (typeof v === 'boolean') flags.push([k, v]);
            else if (typeof v === 'number' && /(_count$|^total_|_total$)/.test(k) && !looksTemporal(k)) counters.push([k, v]);
            else if (v !== null && v !== undefined && v !== '') rest.push([k, v]);
        }

        var active = flags.filter(function (f) { return f[1]; });
        if (flags.length > 0) {
            var badges = el('div', { className: 'profile-flags' });
            if (active.length === 0) {
                badges.appendChild(el('span', { className: 'badge badge-green', textContent: '\u2713 ' + t('nothing-active') }));
            } else {
                active.forEach(function (f) {
                    var symbol = FLAG_SYMBOLS[f[0]] || '\u25CF';
                    var tone = (f[0] === 'premium' || f[0] === 'two_factor_enabled') ? 'badge-green' : 'badge-red';
                    badges.appendChild(el('span', {
                        className: 'badge ' + tone,
                        textContent: symbol + ' ' + fieldLabel(f[0])
                    }));
                });
            }
            container.appendChild(badges);
        }

        var nonZero = counters.filter(function (c) { return c[1] > 0; });
        if (nonZero.length > 0) {
            var grid = el('div', { className: 'stats-grid stats-grid-compact' });
            nonZero.forEach(function (c) {
                addStat(grid, fieldLabel(c[0]), formatNumber(c[1]), c[1] > 0 ? 'orange' : '');
            });
            container.appendChild(grid);
        }

        if (rest.length > 0) {
            var list = el('div', { className: 'kv-list' });
            rest.forEach(function (entry) {
                var row = el('div', { className: 'kv-row' });
                row.appendChild(el('span', { className: 'kv-key', textContent: fieldLabel(entry[0]) }));
                var value = el('span', { className: 'kv-value' });
                value.appendChild(valueNode(entry[0], entry[1]));
                row.appendChild(value);
                list.appendChild(row);
            });
            container.appendChild(list);
        }

        if (!container.hasChildNodes()) {
            container.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-data') })]));
        }
    }


    // ══════════════════════════════════════════════════════════════
    //  MODULE HOME
    // ══════════════════════════════════════════════════════════════

    /**
     * The landing page of one module, built from what it published.
     *
     * It shows the module's own statistics when it has a stats page — a home that only lists links
     * tells the reader nothing they did not already see in the sidebar — followed by a card per
     * page. Works for a module with no descriptor too, minus the statistics.
     */
    function renderModuleHome(moduleName) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';

        var mod = null;
        for (var i = 0; i < modules.length; i++) {
            if (modules[i].name === moduleName) { mod = modules[i]; break; }
        }
        if (!mod) { loadModule(moduleName, ''); return; }

        main.appendChild(el('h1', { className: 'page-title', textContent: mod.name }));

        // Statistics from the module's own stats page, if it declared one.
        var statsPage = (mod.pages || []).filter(function (p) {
            return p.spec && p.spec.type === 'stats' && p.spec.stats && p.spec.stats.length > 0;
        })[0];

        if (statsPage) {
            var grid = el('div', { className: 'stats-grid' });
            grid.innerHTML = '<div class="loading">' + t('loading') + '</div>';
            main.appendChild(grid);
            api(statsPage.spec.endpoint).then(function (data) {
                grid.innerHTML = '';
                statsPage.spec.stats.forEach(function (tile) {
                    var value = pick(data, tile.keys);
                    if (value === null) return;
                    addStat(grid, t(tile.labelKey), formatStatValue(value, tile.format), statColor(tile, value));
                });
                if (!grid.hasChildNodes()) grid.remove();
            }).catch(function () { grid.remove(); });
        }

        var card = el('div', { className: 'card' });
        card.appendChild(el('h3', { textContent: t('pages') }));
        var tiles = el('div', { className: 'page-grid' });

        (mod.pages || []).forEach(function (page) {
            var id = 'module:' + mod.name + ':' + page.path;
            var tile = el('a', {
                className: 'page-tile',
                onClick: function (e) { e.preventDefault(); navigateTo(id); }
            });
            tile.appendChild(el('span', { className: 'page-tile-name', textContent: t(page.name || page.path) }));
            tile.appendChild(el('span', { className: 'page-tile-kind', textContent: pageKindLabel(page) }));
            tiles.appendChild(tile);
        });

        card.appendChild(tiles);
        main.appendChild(card);
    }

    /** A one-word hint of what a page contains, from its descriptor. */
    function pageKindLabel(page) {
        if (!page.spec) return t('page-kind-list');
        if (page.spec.type === 'stats') return t('page-kind-stats');
        if (page.spec.type === 'config') return t('page-kind-config');
        if (page.spec.form) return t('page-kind-manage');
        return t('page-kind-list');
    }


    // ══════════════════════════════════════════════════════════════
    //  DECLARATIVE PAGES
    //
    //  A module describes its pages in Java (WebPageSpec) and /api/modules
    //  ships the description. Everything below renders that description —
    //  no endpoint, label or form of any specific addon appears here.
    // ══════════════════════════════════════════════════════════════

    /** Finds the descriptor a module published for one of its pages. */
    function findSpec(moduleName, path) {
        for (var i = 0; i < modules.length; i++) {
            var mod = modules[i];
            if (mod.name !== moduleName || !mod.pages) continue;
            for (var j = 0; j < mod.pages.length; j++) {
                if (mod.pages[j].path === path) return mod.pages[j].spec || null;
            }
        }
        return null;
    }

    /** Reads the first field of `obj` present among `keys`. */
    function pick(obj, keys) {
        if (!keys) return null;
        for (var i = 0; i < keys.length; i++) {
            var v = obj[keys[i]];
            if (v !== undefined && v !== null) return v;
        }
        return null;
    }

    /** Extracts the row array from a payload that may be an array or wrap one. */
    function extractRows(data, dataKeys) {
        if (Array.isArray(data)) return data;
        if (!data || typeof data !== 'object') return [];
        var named = pick(data, dataKeys);
        if (Array.isArray(named)) return named;
        for (var k in data) { if (Array.isArray(data[k])) return data[k]; }
        return [];
    }

    /** Applies a spec's display format to a raw value. */
    function formatStatValue(value, format) {
        if (typeof value !== 'number') return value;
        if (format === 'megabytes') return Math.round(value / 1024 / 1024) + ' MB';
        if (format === 'decimal') return value.toFixed(1);
        if (format === 'number') return formatNumber(value);
        return value % 1 !== 0 ? value.toFixed(1) : formatNumber(value);
    }

    /**
     * Picks a tile's colour, letting a crossed threshold override the default.
     * The tightest bound wins, so the order they were declared in does not matter.
     */
    function statColor(tile, value) {
        if (typeof value === 'number' && tile.thresholds) {
            var crossed = tile.thresholds
                .filter(function (th) { return value < th.below; })
                .sort(function (a, b) { return a.below - b.below; });
            if (crossed.length > 0) return crossed[0].color;
        }
        return tile.color || '';
    }

    /**
     * Builds a request body by reading each declared parameter off a row.
     *
     * A source written "=value" is a constant rather than a field name: it is what lets one
     * endpoint back two buttons that differ only by a flag.
     */
    function specBody(params, item) {
        var body = {};
        (params || []).forEach(function (p) {
            var from = p.from || [];
            if (from.length === 1 && typeof from[0] === 'string' && from[0].charAt(0) === '=') {
                body[p.param] = from[0].slice(1);
                return;
            }
            var v = pick(item, from);
            if (v !== null) body[p.param] = v;
        });
        return body;
    }

    /** Runs a POST declared by a spec, with confirmation and translated feedback. */
    function runSpecPost(prompt, endpoint, body, onDone) {
        confirmAction(prompt, function () {
            apiPost(endpoint, body).then(function () {
                showToast(t('action-completed-successfully'), 'success');
                if (onDone) onDone();
            }).catch(function (err) {
                showToast(err.message || t('action-failed'), 'error');
            });
        });
    }

    function renderSpecPage(moduleName, path, spec) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: t(spec.titleKey || path) }));

        if (spec.type === 'config') {
            var configCard = el('div', { className: 'card' });
            main.appendChild(configCard);
            renderConfigEditor(moduleName, configCard);
            return;
        }
        if (spec.type === 'stats') { renderSpecStats(main, spec); return; }
        renderSpecTable(main, spec);
    }

    // ──────────────────────────────────────────────
    //  Statistics pages
    // ──────────────────────────────────────────────

    function renderSpecStats(main, spec) {
        var grid = el('div', { className: 'stats-grid' });
        grid.innerHTML = '<div class="loading">' + t('loading') + '</div>';
        main.appendChild(grid);

        var togglesCard = null;
        if (spec.toggles) {
            togglesCard = el('div', { className: 'card' });
            togglesCard.appendChild(el('h3', { textContent: t(spec.toggles.titleKey) }));
            main.appendChild(togglesCard);
        }

        api(spec.endpoint).then(function (data) {
            grid.innerHTML = '';
            var shown = {};

            (spec.stats || []).forEach(function (tile) {
                var val = pick(data, tile.keys);
                if (val === null) return;
                (tile.keys || []).forEach(function (k) { shown[k] = true; });
                addStat(grid, t(tile.labelKey), formatStatValue(val, tile.format), statColor(tile, val));
            });

            if (togglesCard) {
                var source = spec.toggles.from ? (data[spec.toggles.from] || {}) : data;
                if (spec.toggles.from) shown[spec.toggles.from] = true;
                var toggleKeys = Object.keys(source).filter(function (k) { return typeof source[k] === 'boolean'; });
                if (toggleKeys.length === 0) {
                    togglesCard.appendChild(el('div', { className: 'empty-state' },
                        [el('p', { textContent: t(spec.toggles.emptyKey) })]));
                } else {
                    var list = el('div', { className: 'kv-list' });
                    toggleKeys.forEach(function (k) {
                        shown[k] = true;
                        var row = el('div', { className: 'kv-row' });
                        row.appendChild(el('span', { className: 'kv-key', textContent: capitalize(k) }));
                        row.appendChild(el('span', {
                            className: 'badge ' + (source[k] ? 'badge-green' : 'badge-red'),
                            textContent: source[k] ? t('status-enabled') : t('status-disabled')
                        }));
                        list.appendChild(row);
                    });
                    togglesCard.appendChild(list);
                }
            }

            if (spec.details) {
                var rest = Object.keys(data).filter(function (k) {
                    return !shown[k] && typeof data[k] !== 'object' && typeof data[k] !== 'boolean';
                });
                if (rest.length > 0) {
                    var card = el('div', { className: 'card' });
                    card.appendChild(el('h3', { textContent: t(spec.details.titleKey) }));
                    var dl = el('div', { className: 'kv-list' });
                    rest.forEach(function (k) {
                        var row = el('div', { className: 'kv-row' });
                        row.appendChild(el('span', { className: 'kv-key', textContent: capitalize(k) }));
                        row.appendChild(el('span', { className: 'kv-value', textContent: formatCellValue(k, data[k]) }));
                        dl.appendChild(row);
                    });
                    card.appendChild(dl);
                    main.appendChild(card);
                }
            }
        }).catch(function () {
            grid.innerHTML = '';
            grid.appendChild(el('div', { className: 'error-msg', textContent: t(spec.errorKey) }));
        });

        var charts = spec.charts || (spec.chart ? [spec.chart] : []);
        charts.forEach(function (chart) { renderSpecChart(main, spec, chart); });
        if (spec.heatmap) renderSpecHeatmap(main, spec);
    }

    function renderSpecChart(main, spec, chart) {
        chart = chart || spec.chart;
        var card = el('div', { className: 'card' });
        card.appendChild(el('h3', { textContent: t(chart.titleKey) }));
        main.appendChild(card);

        api(chart.endpoint || spec.endpoint).then(function (data) {
            var series = extractRows(data, chart.dataKey ? [chart.dataKey] : null);
            if (chart.maxPoints > 0 && series.length > chart.maxPoints) {
                // More bars than pixels helps nobody: keep an evenly spaced sample of them.
                var step = Math.ceil(series.length / chart.maxPoints);
                series = series.filter(function (_, i) { return i % step === 0; });
            }
            if (series.length === 0) {
                card.appendChild(el('div', { className: 'empty-state' },
                    [el('p', { textContent: t(chart.emptyKey) })]));
                return;
            }
            renderBarChart(card, series, chart.labelField, chart.valueField);
        }).catch(function () {
            card.appendChild(el('div', { className: 'empty-state' },
                [el('p', { textContent: t(chart.emptyKey) })]));
        });
    }

    // ── Heatmap ──
    // A top-down grid: one cell per coordinate pair, coloured by value. Cells are laid out from
    // the extents of the data itself, so a sparse map stays readable instead of drawing a mostly
    // empty world. Colours run green → orange → red across the observed range, because the
    // absolute numbers mean nothing without the neighbours to compare them to.
    function renderSpecHeatmap(main, spec) {
        var conf = spec.heatmap;
        var card = el('div', { className: 'card' });
        card.appendChild(el('h3', { textContent: t(conf.titleKey) }));
        main.appendChild(card);

        api(conf.endpoint || spec.endpoint).then(function (data) {
            var rows = extractRows(data, conf.dataKey ? [conf.dataKey] : null);
            if (!rows.length) {
                card.appendChild(el('div', { className: 'empty-state' },
                    [el('p', { textContent: t(conf.emptyKey) })]));
                return;
            }

            var minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity, maxValue = 0;
            rows.forEach(function (row) {
                var x = Number(row[conf.xField]), z = Number(row[conf.zField]);
                var v = Number(row[conf.valueField]) || 0;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
                if (v > maxValue) maxValue = v;
            });

            var columns = Math.max(1, maxX - minX + 1);
            var lines = Math.max(1, maxZ - minZ + 1);
            var cells = {};
            rows.forEach(function (row) {
                cells[Number(row[conf.xField]) + ':' + Number(row[conf.zField])] = row;
            });

            var grid = el('div', { className: 'heatmap' });
            grid.style.display = 'grid';
            grid.style.gridTemplateColumns = 'repeat(' + columns + ', minmax(6px, 1fr))';
            grid.style.gap = '1px';
            grid.style.marginTop = '12px';

            for (var line = 0; line < lines; line++) {
                for (var col = 0; col < columns; col++) {
                    var row = cells[(minX + col) + ':' + (minZ + line)];
                    var cell = el('div');
                    cell.style.aspectRatio = '1';
                    cell.style.borderRadius = '2px';
                    if (!row) {
                        cell.style.background = 'rgba(255,255,255,0.04)';
                    } else {
                        var value = Number(row[conf.valueField]) || 0;
                        var ratio = maxValue > 0 ? value / maxValue : 0;
                        var hue = 120 - Math.round(120 * Math.min(1, ratio));
                        cell.style.background = 'hsl(' + hue + ', 70%, ' + (28 + Math.round(22 * ratio)) + '%)';
                        cell.title = conf.xField + ' ' + row[conf.xField]
                            + ' / ' + conf.zField + ' ' + row[conf.zField]
                            + ' — ' + value;
                    }
                    grid.appendChild(cell);
                }
            }
            card.appendChild(grid);
        }).catch(function () {
            card.appendChild(el('div', { className: 'empty-state' },
                [el('p', { textContent: t(conf.emptyKey) })]));
        });
    }

    // ──────────────────────────────────────────────
    //  Table pages
    // ──────────────────────────────────────────────

    function renderSpecTable(main, spec) {
        var state = { page: 1, search: '', filters: {} };
        var container = el('div');

        if (spec.bulk) main.appendChild(specBulkBar(spec, load));
        if (spec.form) main.appendChild(specForm(spec, load));
        main.appendChild(container);

        function url() {
            var u = spec.endpoint;
            var parts = [];
            if (spec.pageSize) parts.push('page=' + state.page, 'limit=' + spec.pageSize);
            if (state.search) parts.push('search=' + encodeURIComponent(state.search));
            for (var key in state.filters) {
                if (state.filters[key]) parts.push(key + '=' + encodeURIComponent(state.filters[key]));
            }
            return parts.length === 0 ? u : u + (u.indexOf('?') === -1 ? '?' : '&') + parts.join('&');
        }

        function load() {
            container.innerHTML = '<div class="loading">' + t('loading') + '</div>';
            api(url()).then(function (data) {
                container.innerHTML = '';
                var items = extractRows(data, spec.dataKeys);

                // A page size means the reader should never face a thousand rows at once. When the
                // endpoint honours page/limit it has already cut them; when it answers with
                // everything — most of them do — the cut happens here rather than not at all.
                var serverPaged = data && (data.total !== undefined || data.page !== undefined);
                var clientTotal = items.length;
                if (spec.pageSize && !serverPaged) {
                    var from = (state.page - 1) * spec.pageSize;
                    if (from >= items.length && state.page > 1) { state.page = 1; from = 0; }
                    items = items.slice(from, from + spec.pageSize);
                }

                if (spec.searchKey || (spec.filters && spec.filters.length > 0)) {
                    renderSearchBar(container, {
                        placeholder: spec.searchKey ? t(spec.searchKey) : t('search'),
                        value: state.search,
                        filters: (spec.filters || []).map(function (f) {
                            return {
                                key: f.param,
                                placeholder: t(f.placeholderKey),
                                options: f.options.map(function (o) {
                                    return { value: o.value, label: t(o.labelKey) };
                                })
                            };
                        }),
                        filterValues: state.filters,
                        onSearch: function (value, filters) {
                            state.search = value;
                            state.filters = filters || {};
                            state.page = 1;
                            load();
                        }
                    });
                }

                if (items.length === 0) {
                    container.appendChild(el('div', { className: 'card' }, [
                        el('div', { className: 'empty-state' }, [el('p', { textContent: t(spec.emptyKey) })])
                    ]));
                    return;
                }

                var card = el('div', { className: 'card' });
                var rowActions = specActions(spec, load);
                renderDataTable(card, items, rowActions.length > 0 ? { actions: rowActions } : {});
                container.appendChild(card);

                if (spec.pageSize) {
                    var total = serverPaged
                        ? pick(data, spec.totalKeys && spec.totalKeys.length
                            ? spec.totalKeys : ['total', 'total_count'])
                        : clientTotal;
                    var totalPages = Math.max(1, Math.ceil((total || items.length) / spec.pageSize));
                    if (totalPages > 1) {
                        renderPagination(container, state.page, totalPages, function (pg) {
                            state.page = pg;
                            load();
                        });
                    }
                }
            }).catch(function () {
                container.innerHTML = '';
                container.appendChild(el('div', { className: 'error-msg', textContent: t(spec.errorKey) }));
            });
        }

        load();
    }

    /** Turns the declared row actions into buttons for renderDataTable. */
    function specActions(spec, reload) {
        return (spec.actions || []).map(function (a) {
            var label = t(a.labelKey);
            return {
                label: label,
                cls: a.style ? 'btn-' + a.style : '',
                handler: function (item) {
                    var id = pick(item, a.idFrom) || '';
                    var prompt = t('confirm-action-target', { action: label, target: id });
                    runSpecPost(prompt, a.endpoint, specBody(a.body, item),
                        a.reload === false ? null : reload);
                }
            };
        });
    }

    /** Builds the page-wide button declared by `bulk`. */
    function specBulkBar(spec, reload) {
        var bar = el('div', { className: 'action-bar' });
        bar.appendChild(el('button', {
            className: 'btn' + (spec.bulk.style ? ' btn-' + spec.bulk.style : ''),
            textContent: t(spec.bulk.labelKey),
            onClick: function () {
                runSpecPost(t(spec.bulk.confirmKey), spec.bulk.endpoint, {}, reload);
            }
        }));
        return bar;
    }

    /** Builds the creation form declared by `form`. */
    function specForm(spec, reload) {
        var form = el('div', { className: 'action-form' });
        var inputs = {};
        var fields = spec.form.fields || [];

        fields.forEach(function (f) {
            var field = el('div', { className: 'form-field' });
            field.appendChild(el('label', { textContent: t(f.labelKey) }));
            var input = el('input', {
                type: f.type || 'text',
                placeholder: f.placeholderKey ? t(f.placeholderKey) : ''
            });
            field.appendChild(input);
            form.appendChild(field);
            inputs[f.key] = input;
        });

        var label = t(spec.form.titleKey);
        form.appendChild(el('button', {
            className: 'btn' + (spec.form.style ? ' btn-' + spec.form.style : ''),
            textContent: label,
            onClick: function () {
                var body = {};
                var valid = true;
                fields.forEach(function (f) {
                    var value = inputs[f.key].value.trim();
                    if (f.required && !value) valid = false;
                    if (value) body[f.key] = value;
                });
                if (!valid) { showToast(t('please-fill-in-all-required-fields'), 'error'); return; }
                var prompt = t('confirm-action-target', { action: label, target: body.player || '' });
                runSpecPost(prompt, spec.form.endpoint, body, function () {
                    fields.forEach(function (f) { inputs[f.key].value = ''; });
                    reload();
                });
            }
        }));
        return form;
    }


    // ══════════════════════════════════════════════════════════════
    //  GENERIC MODULE PAGE (fallback)
    // ══════════════════════════════════════════════════════════════

    function loadModule(name, path) {
        var main = document.getElementById('main-content');
        main.innerHTML = '';
        main.appendChild(el('h1', { className: 'page-title', textContent: name + ' / ' + capitalize(path) }));

        var card = el('div', { className: 'card' });
        card.innerHTML = '<div class="loading">' + t('loading') + '</div>';
        main.appendChild(card);

        // Generic raw config editor: any module exposing a "config" page + /config/raw GET+POST.
        if (path === 'config') { renderConfigEditor(name, card); return; }

        // Transactions: load all immediately, with optional player filter
        if (path === 'transactions') {
            card.innerHTML = '';
            var filterBar = el('div', { className: 'filter-bar' });
            filterBar.appendChild(el('label', { textContent: t('filter-by-player') }));
            var input = el('input', { type: 'text', placeholder: t('player-name-optional') });
            filterBar.appendChild(input);
            var btn = el('button', {
                className: 'btn', textContent: t('filter'),
                onClick: function () {
                    var val = input.value.trim();
                    var url = '/api/' + name.toLowerCase() + '/' + path + '?offset=0&limit=100';
                    if (val) url += '&player=' + encodeURIComponent(val);
                    loadModuleData(card, url);
                }
            });
            filterBar.appendChild(btn);
            var clearBtn = el('button', {
                className: 'btn btn-secondary', textContent: t('clear'),
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

    // Raw YAML config editor — generic, works for any module exposing /api/<name>/config/raw.
    function renderConfigEditor(name, card) {
        var lower = name.toLowerCase();
        var base = '/api/' + lower + '/config/raw';
        card.innerHTML = '<div class="loading">' + t('loading') + '</div>';

        api(base).then(function (data) {
            card.innerHTML = '';

            card.appendChild(el('p', {
                className: 'config-hint',
                textContent: t('config-editor-hint', { addon: name })
            }));

            var textarea = el('textarea', { className: 'config-editor', spellcheck: 'false' });
            textarea.value = data.yaml || '';
            card.appendChild(textarea);

            var actions = el('div', { className: 'config-actions' });
            var status = el('span', { className: 'config-status' });

            var saveBtn = el('button', {
                className: 'btn', textContent: t('save-reload'),
                onClick: function () {
                    saveBtn.disabled = true;
                    status.className = 'config-status';
                    status.textContent = t('saving');
                    apiPost(base, { yaml: textarea.value }).then(function () {
                        saveBtn.disabled = false;
                        status.textContent = '';
                        showToast(t('config-saved-and-reloaded', { addon: name }), 'success');
                    }).catch(function (err) {
                        saveBtn.disabled = false;
                        var msg = (err && err.error) ? err.error : t('save-failed');
                        status.className = 'config-status config-error';
                        status.textContent = msg;
                        showToast(t('save-failed-see-message-below-the-editor'), 'error');
                    });
                }
            });

            var reloadBtn = el('button', {
                className: 'btn btn-secondary', textContent: t('revert-reload-from-disk'),
                onClick: function () { renderConfigEditor(name, card); }
            });

            actions.appendChild(saveBtn);
            actions.appendChild(reloadBtn);
            actions.appendChild(status);
            card.appendChild(actions);
        }).catch(function () {
            card.innerHTML = '';
            card.appendChild(el('div', { className: 'empty-state' }, [
                el('p', { textContent: t('could-not-load-config-yml-for') + name + '.' })
            ]));
        });
    }

    function loadModuleData(card, url) {
        var results = card.querySelector('#module-results') || card;
        results.innerHTML = '<div class="loading">' + t('loading') + '</div>';

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
                results.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-data-found') })]));
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
            results.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-data') }));
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

    /**
     * The texture of a material, served by XCore itself.
     *
     * <p>Pointing the browser at a public mirror looked simpler and failed for reasons a page can
     * never see: a mirror that answers a command line and refuses a browser, an extension blocking
     * the domain, a DNS filter. The server fetches and caches it instead, so this is a same-origin
     * request like any other — and it fails visibly, in the server log, rather than silently.</p>
     */
    /** Minecraft's own translations, keyed as the game keys them. Empty until loaded. */
    var mcLang = {};

    function loadMinecraftLang() {
        api('/api/mclang').then(function (data) {
            if (!data || typeof data !== 'object') return;
            var wasEmpty = Object.keys(mcLang).length === 0;
            mcLang = data;
            // Half a megabyte fetched from an asset mirror lands well after the first page is
            // drawn. Without this, names stayed in English until the reader happened to navigate
            // somewhere else and back.
            if (wasEmpty && currentPage && document.querySelector('.item-cell')) {
                navigateTo(currentPage);
            }
        }).catch(function () {});
    }

    /**
     * The name of an item, in the reader's language when Minecraft has one for it.
     *
     * <p>Falls back to the material name the module formatted, so an item the game does not name —
     * anything a plugin invented — still reads properly.</p>
     */
    function itemName(details) {
        if (details.customName) return details.customName;
        var translated = details.translationKey ? mcLang[details.translationKey] : null;
        return translated || details.displayMaterial || details.material;
    }

    function itemSprite(material) {
        var name = encodeURIComponent(String(material).toLowerCase());
        var img = el('img', { className: 'item-sprite', alt: '', loading: 'lazy' });
        // A material no mirror carries answers 404: drop the image rather than show a broken one.
        img.onerror = function () { img.style.display = 'none'; };
        img.src = '/api/sprite/' + name + '.png';
        return img;
    }

    function renderItemCell(details) {
        var cell = el('td', { className: 'item-cell' });
        if (!details || !details.material) { cell.textContent = '-'; return cell; }

        cell.appendChild(itemSprite(details.material));

        var name = itemName(details);
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
            tooltip.appendChild(el('div', { className: 'mc-tooltip-durability', textContent: t('durability') + remaining + '/' + details.maxDurability }));
        }
        if (details.amount > 1) {
            tooltip.appendChild(el('div', { className: 'mc-tooltip-amount', textContent: t('amount') + details.amount }));
        }

        cell.appendChild(tooltip);
        return cell;
    }

    function renderDataTable(container, items, opts) {
        opts = opts || {};
        // An empty array is truthy, which is how tables with no action ended up with an empty
        // "Actions" column — a header with nothing under it, pushing the layout around.
        var actions = (opts.actions && opts.actions.length > 0) ? opts.actions : null;
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
            headRow.appendChild(el('th', { textContent: t('item') }));
        }

        keys.forEach(function (k) {
            // A right-aligned figure under a left-aligned heading reads as a broken column.
            var numeric = items.some(function (row) { return typeof row[k] === 'number'; });
            headRow.appendChild(el('th', {
                className: numeric ? 'num-cell' : '',
                textContent: fieldLabel(k)
            }));
        });

        if (actions) {
            headRow.appendChild(el('th', { className: 'col-actions', textContent: t('actions') }));
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
                    } else if (typeof val === 'boolean') {
                        td.appendChild(valueNode(k, val, true));
                    } else {
                        td.textContent = formatCellValue(k, val);
                        if (typeof val === 'number') td.className = 'num-cell';
                        if (k.indexOf('uuid') !== -1 || k.indexOf('UUID') !== -1) td.className = 'uuid-cell';
                    }
                }
                row.appendChild(td);
            });

            if (actions) {
                var actionTd = el('td', { className: 'col-actions' });
                actions.forEach(function (action) {
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
    //
    //  What is left here belongs to modules that still describe their pages
    //  the old way. A module that publishes a WebPageSpec never reaches this
    //  registry — see the declarative renderer above.
    // ══════════════════════════════════════════════════════════════

    var moduleRenderers = {};


    // ──────────────────────────────────────────────
    //  XShops Module
    // ──────────────────────────────────────────────

    moduleRenderers['XShops'] = {

        // -- Shops --
        shops: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: t('shops-overview') }));

            var card = el('div', { className: 'card' });
            card.innerHTML = '<div class="loading">' + t('loading') + '</div>';
            main.appendChild(card);

            api('/api/xshops/shops').then(function (data) {
                var items = Array.isArray(data) ? data : (data.shops || data.data || []);
                card.innerHTML = '';
                if (!items || items.length === 0) {
                    card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-shops-found') })]));
                    return;
                }
                renderDataTable(card, items);
            }).catch(function () {
                card.innerHTML = '';
                card.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-shops') }));
            });
        },

        // -- Stock --
        stock: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: t('shops-stock') }));

            var actionBar = el('div', { className: 'action-bar' });
            actionBar.appendChild(el('button', {
                className: 'btn btn-success',
                textContent: t('restock-all'),
                onClick: function () {
                    confirmAction(t('restock-all-shops-to-their-maximum'), function () {
                        apiPost('/api/xshops/stock/restock', {}).then(function () {
                            showToast(t('all-shops-restocked'), 'success');
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
                card.innerHTML = '<div class="loading">' + t('loading') + '</div>';
                api('/api/xshops/stock').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.stock || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-stock-data') })]));
                        return;
                    }
                    renderStockTable(card, items);
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-stock') }));
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
                                            showToast(t('stock-updated'), 'success');
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
            main.appendChild(el('h1', { className: 'page-title', textContent: t('shops-prices') }));

            var actionBar = el('div', { className: 'action-bar' });
            actionBar.appendChild(el('button', {
                className: 'btn btn-warning',
                textContent: t('reset-all-prices'),
                onClick: function () {
                    confirmAction(t('reset-all-prices-to-default-this-cannot-be-undon'), function () {
                        apiPost('/api/xshops/prices/reset', {}).then(function () {
                            showToast(t('all-prices-reset'), 'success');
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
                card.innerHTML = '<div class="loading">' + t('loading') + '</div>';
                api('/api/xshops/prices').then(function (data) {
                    var items = Array.isArray(data) ? data : (data.prices || data.data || []);
                    card.innerHTML = '';
                    if (!items || items.length === 0) {
                        card.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-price-data') })]));
                        return;
                    }
                    renderDataTable(card, items);
                }).catch(function () {
                    card.innerHTML = '';
                    card.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-prices') }));
                });
            }

            loadPrices();
        },

        // -- Transactions --
        transactions: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: t('shops-transactions') }));

            var state = { page: 1, search: '', action: '' };
            var resultsContainer = el('div');
            main.appendChild(resultsContainer);

            function loadPage() {
                resultsContainer.innerHTML = '<div class="loading">' + t('loading') + '</div>';
                var url = '/api/xshops/transactions/search?page=' + state.page + '&limit=50';
                if (state.search) url += '&player=' + encodeURIComponent(state.search);
                if (state.action) url += '&action=' + encodeURIComponent(state.action);

                api(url).then(function (data) {
                    resultsContainer.innerHTML = '';
                    var items = data.transactions || data.data || (Array.isArray(data) ? data : []);
                    var total = data.total || data.total_count || items.length;
                    var totalPages = Math.max(1, Math.ceil(total / 50));

                    renderSearchBar(resultsContainer, {
                        placeholder: t('search-by-player'),
                        value: state.search,
                        filters: [{
                            key: 'action',
                            placeholder: t('all-actions'),
                            options: [
                                { value: 'BUY', label: t('buy') },
                                { value: 'SELL', label: t('sell') }
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
                            el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-transactions-found') })])
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
                    resultsContainer.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-transactions') }));
                });
            }

            loadPage();
        },

        // -- Statistics --
        stats: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: t('shops-statistics') }));

            var grid = el('div', { className: 'stats-grid' });
            grid.innerHTML = '<div class="loading">' + t('loading') + '</div>';
            main.appendChild(grid);

            var chartCard = el('div', { className: 'card' });
            chartCard.appendChild(el('h3', { textContent: t('daily-sales-volume') }));
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
                grid.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-stats') }));
            });

            api('/api/xshops/stats/daily').then(function (data) {
                var days = Array.isArray(data) ? data : (data.days || data.data || []);
                renderBarChart(chartCard, days, 'date', 'volume');
            }).catch(function () {
                chartCard.appendChild(el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-daily-data-available') })]));
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
            main.appendChild(el('h1', { className: 'page-title', textContent: t('automessage-messages') }));

            var actionBar = el('div', { className: 'action-bar' });
            actionBar.appendChild(el('button', {
                className: 'btn',
                textContent: t('reload-messages'),
                onClick: function () {
                    confirmAction(t('reload-all-messages-from-config'), function () {
                        apiPost('/api/automessage/reload', {}).then(function () {
                            showToast(t('messages-reloaded'), 'success');
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
                container.innerHTML = '<div class="loading">' + t('loading') + '</div>';
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
                                    showToast(t('message') + (checked ? 'enabled' : 'disabled') + '.', 'success');
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
                            el('div', { className: 'empty-state' }, [el('p', { textContent: t('no-messages-configured') })])
                        ]));
                    }
                }).catch(function () {
                    container.innerHTML = '';
                    container.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-messages') }));
                });
            }

            loadMessages();
        },

        // -- Status --
        status: function () {
            var main = document.getElementById('main-content');
            main.innerHTML = '';
            main.appendChild(el('h1', { className: 'page-title', textContent: t('automessage-status') }));

            var card = el('div', { className: 'card' });
            card.innerHTML = '<div class="loading">' + t('loading') + '</div>';
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
                card.appendChild(el('div', { className: 'error-msg', textContent: t('failed-to-load-status') }));
            });
        }
    };


    // ══════════════════════════════════════════════════════════════
    //  INIT
    // ══════════════════════════════════════════════════════════════

    /**
     * Picks up the token from a /xcore dashboard link and stores it.
     *
     * The link is single-use in practice: the token is taken out of the address bar and out of the
     * history entry straight away, so it does not sit in a shared screenshot or a bookmark.
     */
    function consumeLoginLink() {
        var match = /[?&#]token=([^&#]+)/.exec(window.location.search + window.location.hash);
        if (!match) return;
        setToken(decodeURIComponent(match[1]));
        var clean = window.location.pathname;
        if (window.history && window.history.replaceState) window.history.replaceState(null, '', clean);
        else window.location.replace(clean);
    }

    function init() {
        consumeLoginLink();
        // Strings first: everything rendered below asks for them.
        loadStrings().then(function () {
            var token = getToken();
            if (!token) {
                renderLogin();
                return;
            }
            api('/api/auth').then(function () {
                renderDashboard();
            }).catch(function () {
                clearToken();
                renderLogin(t('session-expired'));
            });
        });
    }

    init();
})();

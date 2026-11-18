// The home page: the server, then the overview.
//
// At the top, the site's home stage turned towards the server: a block of glass, pinned while the
// page scrolls, turning a quarter for each live figure — players online, TPS, memory, plugins —
// while the words beside it change. Then the overview the dashboard always had: the figures as
// tiles, the installation, the plugins, the players seen last.
//
// The motion itself is in css/home.css, computed from one number, --t. Where the browser supports
// scroll-driven animations the scroll animates --t and this file only keeps track of which figure
// is on screen; elsewhere it eases --t towards the scroll position each frame, as the site does,
// and stops the moment it arrives, so an idle page costs nothing.

import { h, icon, swap, setVar, reducedMotion } from "../lib/dom.js";
import { attachLens } from "../lib/glass.js";
import { get } from "../lib/api.js";
import { t, num, fixed, bytes, duration, relative, dateTime } from "../lib/i18n.js";
import { allModules, markOf, moduleHref, playerHref } from "../lib/modules.js";
import { onMetrics, wantEvery, tpsState, memoryState, STATE_TONE } from "../lib/metrics.js";
import { glassBlock } from "../ui/block.js";
import { face, playerLink, emptyState } from "../ui/table.js";

const BEATS = 4;
const PINNED_QUERY = "(prefers-reduced-motion: no-preference) and (scripting: enabled) and (min-height: 540px)";
const MATH = "calc(round(down, 1.5px, 1px) + sin(30deg) * 1px + pow(2, 1) * 1px)";

/** How long the eased position takes to close most of the gap, in ms: the site's values. */
const LAG_MOUSE = 115;
const LAG_TOUCH = 70;

// ---------------------------------------------------------------------------
// The sky: two sheets of stars, the site's, seeded so they are the same on every visit.
// ---------------------------------------------------------------------------

function scatter(count, seed, minRadius, maxRadius) {
  let state = seed;
  const next = () => (state = (state * 16807) % 2147483647) / 2147483647;
  return Array.from({ length: count }, () => ({
    x: Math.round(next() * 16000) / 10,
    y: Math.round(next() * 10000) / 10,
    r: Math.round((minRadius + next() * (maxRadius - minRadius)) * 100) / 100,
    o: Math.round((0.25 + next() * 0.75) * 100) / 100,
  }));
}

function sky() {
  const sheet = (stars, depth) => {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 1600 1000");
    svg.setAttribute("preserveAspectRatio", "xMidYMid slice");
    svg.dataset.sky = depth;
    svg.innerHTML = stars.map((s) => `<circle cx="${s.x}" cy="${s.y}" r="${s.r}" opacity="${s.o}"/>`).join("");
    return svg;
  };
  return h("div", { class: "scene-sky", "aria-hidden": "true" }, sheet(scatter(90, 11, 0.5, 1.1), "far"), sheet(scatter(34, 97, 0.9, 1.8), "near"));
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

/** "git-Paper-196 (MC: 1.21.4)" reads "Paper 1.21.4". */
function software(raw) {
  if (!raw) return "";
  const match = /([A-Za-z]+)-[\w.]+\s*\(MC:\s*([\d.]+)\)/.exec(raw);
  return match ? `${match[1]} ${match[2]}` : String(raw).split(" (")[0];
}

function tile(label, tone = null) {
  const value = h("p", { class: "tile-value", text: "—" });
  const note = h("p", { class: "tile-note", hidden: true });
  const node = h(
    "div",
    { class: "tile", data: { tone } },
    h("p", { class: "tile-label label" }, h("span", { class: "tile-dot" }), h("span", { text: label })),
    value,
    note
  );
  return {
    node,
    set(text, { tone: next = tone, alert = false, hint = "" } = {}) {
      value.textContent = text;
      if (next) node.dataset.tone = next;
      else delete node.dataset.tone;
      node.toggleAttribute("data-alert", alert);
      note.hidden = !hint;
      note.textContent = hint;
    },
  };
}

function kvRow(label, value) {
  return h("div", { class: "kv-row" }, h("span", { class: "kv-key", text: label }), h("span", { class: "kv-value", text: value }));
}

// ---------------------------------------------------------------------------
// The page
// ---------------------------------------------------------------------------

export function homePage() {
  const cleanups = [];

  // --- Opening ---------------------------------------------------------------

  const dot = h("i", { class: "live-dot" });
  const eyebrowText = h("span", { text: t("xcore") });
  const title = h("h1", { class: "scene-title", text: t("server") });
  const lede = h("p", { class: "scene-lede", text: t("loading") });
  const chipPlayers = h("strong", { text: "—" });
  const chipTps = h("strong", { text: "—" });
  const chipMemory = h("strong", { text: "—" });
  const chipTpsBox = h("span", { class: "chip" }, icon("activity"), chipTps, h("span", { text: t("tps") }));
  const chipMemoryBox = h("span", { class: "chip" }, icon("memory-stick"), chipMemory, h("span", { text: t("memory") }));

  const hero = h(
    "header",
    { class: "scene-hero", data: { hero: true } },
    h("p", { class: "scene-eyebrow label enter", vars: { i: 0 } }, dot, eyebrowText),
    h("div", { class: "enter", vars: { i: 1 } }, title),
    h("p", { class: "enter", vars: { i: 2 } }, lede),
    h(
      "div",
      { class: "scene-chips enter", vars: { i: 3 } },
      h("span", { class: "chip" }, icon("users"), chipPlayers, h("span", { text: t("online") })),
      chipTpsBox,
      chipMemoryBox
    ),
    h(
      "div",
      { class: "scene-actions enter", vars: { i: 4 } },
      h("a", { class: "btn glass-brass", href: "#/", data: { jump: 0 } }, h("span", { text: t("see-figures") }), icon("arrow-down")),
      h("a", { class: "btn btn-secondary glass glass-set", href: "#/", data: { jump: "overview" } }, h("span", { text: t("skip-to-overview") }))
    )
  );

  // --- The four figures ----------------------------------------------------------

  const figures = [
    { key: "players", icon: "users", label: t("players"), mark: "var(--accent)" },
    { key: "tps", icon: "activity", label: t("tps"), mark: "var(--success)" },
    { key: "memory", icon: "memory-stick", label: t("memory"), mark: "var(--info)" },
    { key: "plugins", icon: "puzzle", label: t("plugins-title"), mark: "var(--p-framework)" },
  ];

  const parts = {};
  const beats = figures.map((figure, index) => {
    const value = h("span", { class: "beat-value", text: "—" });
    const unit = h("span", { class: "beat-unit" });
    const figureLine = h("p", { class: "beat-figure" }, value, unit);
    const text = h("p", { class: "beat-text" });
    const extra = h("div", { class: "beat-extra" });
    parts[figure.key] = { value, unit, figureLine, text, extra };
    return h(
      "li",
      { class: "beat", data: { beat: index }, vars: { i: index, mark: figure.mark } },
      h(
        "p",
        { class: "beat-eyebrow label" },
        icon(figure.icon),
        h("span", { text: figure.label }),
        h("span", { class: "beat-count num", text: `${String(index + 1).padStart(2, "0")} / ${String(BEATS).padStart(2, "0")}` })
      ),
      figureLine,
      text,
      extra
    );
  });

  const block = glassBlock(figures);
  const glows = [h("span", { class: "scene-glow", data: { glow: 0 }, vars: { g: 0, mark: "var(--accent)" } })].concat(
    figures.map((figure, index) => h("span", { class: "scene-glow", data: { glow: index + 1 }, vars: { g: index + 1, mark: figure.mark } }))
  );

  const pill = h("i", { class: "rail-pill", "aria-hidden": "true" });
  const stops = figures.map((figure, index) =>
    h(
      "button",
      { type: "button", data: { jump: index }, "aria-label": figure.label, vars: { mark: figure.mark } },
      icon(figure.icon),
      h("span", { class: "rail-name", text: figure.label })
    )
  );
  const rail = h("nav", { class: "rail glass", "aria-label": t("scene-figures") }, h("ol", null, pill, stops.map((stop) => h("li", null, stop))));
  const cue = h("div", { class: "scene-cue", "aria-hidden": "true" }, h("span", { class: "label", text: t("scroll") }), h("i"));

  const pin = h(
    "div",
    { class: "scene-pin" },
    sky(),
    h(
      "div",
      { class: "scene-inner" },
      h("div", { class: "scene-copy" }, hero, h("ol", { class: "beats" }, beats)),
      h("div", { class: "scene-object" }, glows, block)
    ),
    rail,
    cue
  );
  const scene = h("section", { class: "scene", "aria-label": t("server") }, pin);

  // --- The overview ----------------------------------------------------------------

  // The server's name is the title of the page and of the sidebar's chip: no tile repeats it.
  const tiles = {
    players: tile(t("players"), "success"),
    tps: tile(t("tps")),
    uptime: tile(t("uptime"), "info"),
    memory: tile(t("memory")),
    addons: tile(t("plugins-title"), "purple"),
    cache: tile(t("cache-hit-rate")),
  };
  const systemList = h("div", { class: "kv" }, h("span", { class: "skel" }));
  const recentList = h("div", { class: "recent" }, h("span", { class: "skel" }));
  const moduleGrid = h("div", { class: "module-cards" });

  const overview = h(
    "section",
    { class: "overview", id: "overview", "aria-labelledby": "overview-title" },
    h("div", { class: "overview-head" }, h("h2", { class: "overview-title", id: "overview-title", text: t("overview") })),
    h("div", { class: "tiles" }, Object.values(tiles).map((entry) => entry.node)),
    h(
      "div",
      { class: "overview-grid" },
      h(
        "div",
        { class: "stack" },
        h("section", { class: "card" }, h("div", { class: "card-head" }, h("h3", { class: "card-title" }, icon("puzzle"), t("plugins-title"))), moduleGrid),
        h("section", { class: "card" }, h("div", { class: "card-head" }, h("h3", { class: "card-title" }, icon("users"), t("recent-players")), h("a", { class: "btn btn-xs btn-quiet", href: "#/players" }, t("players"), icon("arrow-right"))), recentList)
      ),
      h("section", { class: "card" }, h("div", { class: "card-head" }, h("h3", { class: "card-title" }, icon("server"), t("system"))), systemList)
    )
  );

  const el = h("div", { class: "page page-home" }, scene, overview);

  // --- Filling it in ---------------------------------------------------------------

  const fillModules = () => {
    const mods = allModules();
    if (mods.length === 0) {
      swap(moduleGrid, emptyState(t("no-addon-installed"), "puzzle"));
      return;
    }
    swap(
      moduleGrid,
      mods.map((mod) => {
        const { mark, icon: iconName } = markOf(mod);
        const count = Array.isArray(mod.pages) ? mod.pages.length : 0;
        return h(
          "a",
          { class: "card module-card", href: moduleHref(mod), vars: { mark } },
          h(
            "div",
            { class: "module-card-top" },
            h("span", { class: "mark-tile" }, icon(iconName)),
            h("div", null, h("p", { class: "module-card-name", text: mod.name }), h("p", { class: "module-card-meta", text: `${num(count)} ${t("pages").toLowerCase()}` })),
            icon("arrow-right", "icon-go")
          )
        );
      })
    );
    swap(
      parts.plugins.extra,
      h(
        "div",
        { class: "module-chips" },
        mods.map((mod) => {
          const { mark, icon: iconName } = markOf(mod);
          return h("a", { class: "module-chip glass glass-set", href: moduleHref(mod), vars: { mark } }, h("span", { class: "mark-tile" }, icon(iconName)), mod.name);
        })
      )
    );
  };
  fillModules();

  let online = [];

  const fill = (m) => {
    const tpsNow = typeof m.tps === "number" ? m.tps : null;
    const tpsLevel = tpsState(tpsNow);
    const memLevel = memoryState(m.memory_used, m.memory_max);
    const memShare = typeof m.memory_used === "number" && m.memory_max > 0 ? m.memory_used / m.memory_max : null;
    online = Array.isArray(m.online_players) ? m.online_players : [];

    // The opening.
    if (m.server_name) title.textContent = m.server_name;
    eyebrowText.textContent = [m.xcore_version ? `XCore ${m.xcore_version}` : t("xcore"), software(m.server_software)].filter(Boolean).join(" · ");
    if (tpsLevel) dot.dataset.state = tpsLevel;
    lede.textContent = typeof m.uptime_seconds === "number" ? t("scene-lede", { uptime: duration(m.uptime_seconds) }) : "";
    chipPlayers.textContent = num(m.players_online ?? 0);
    chipTps.textContent = tpsNow === null ? "—" : fixed(tpsNow, 1);
    chipTpsBox.dataset.tone = tpsLevel ? STATE_TONE[tpsLevel] : "";
    chipMemory.textContent = memShare === null ? "—" : num(memShare, { style: "percent" });
    chipMemoryBox.dataset.tone = memLevel ? STATE_TONE[memLevel] : "";
    chipMemoryBox.hidden = memShare === null;

    // Players.
    const p = parts.players;
    p.value.textContent = num(m.players_online ?? 0);
    p.unit.textContent = m.players_max ? t("of-max", { max: num(m.players_max) }) : "";
    p.text.textContent = t("beat-players");
    const shown = online.slice(0, 8);
    swap(
      p.extra,
      shown.length
        ? h(
            "div",
            { class: "faces-row" },
            shown.map((name) => h("a", { href: playerHref(name), title: name, "aria-label": name }, face(name, 32))),
            online.length > shown.length ? h("span", { class: "more num", text: `+${num(online.length - shown.length)}` }) : null
          )
        : null
    );

    // TPS.
    const tp = parts.tps;
    tp.value.textContent = tpsNow === null ? "—" : fixed(tpsNow, 1);
    tp.unit.textContent = tpsNow === null ? "" : "/ 20";
    // The figure takes a colour only when it is a warning: a healthy server reads in plain ink.
    if (tpsLevel && tpsLevel !== "ok") tp.figureLine.dataset.tone = STATE_TONE[tpsLevel];
    else delete tp.figureLine.dataset.tone;
    tp.text.textContent = t(tpsLevel ? `beat-tps-${tpsLevel}` : "beat-tps-none");
    swap(
      tp.extra,
      tpsNow === null
        ? null
        : [
            h("div", { class: "meter", data: { tone: tpsLevel ? STATE_TONE[tpsLevel] : null } }, h("i", { vars: { share: Math.min(1, tpsNow / 20).toFixed(3) } })),
            h("p", { class: "meter-legend num" }, h("span", { text: "0" }), h("span", { text: "20" })),
          ]
    );

    // Memory.
    const me = parts.memory;
    me.value.textContent = typeof m.memory_used === "number" ? bytes(m.memory_used) : "—";
    me.unit.textContent = typeof m.memory_max === "number" ? t("of-max", { max: bytes(m.memory_max) }) : "";
    me.text.textContent = memShare === null ? t("beat-memory-none") : t("beat-memory", { percent: num(memShare, { style: "percent" }) });
    if (memLevel && memLevel !== "ok") me.figureLine.dataset.tone = STATE_TONE[memLevel];
    else delete me.figureLine.dataset.tone;
    swap(
      me.extra,
      memShare === null
        ? null
        : h("div", { class: "meter", data: { tone: memLevel ? STATE_TONE[memLevel] : null } }, h("i", { vars: { share: memShare.toFixed(3) } }))
    );

    // Plugins.
    const pl = parts.plugins;
    const enabled = m.addons_enabled ?? m.modules_count;
    pl.value.textContent = enabled === undefined || enabled === null ? "—" : num(enabled);
    pl.unit.textContent = typeof m.addons_total === "number" ? t("of-max", { max: num(m.addons_total) }) : "";
    pl.text.textContent = t("beat-plugins", { version: m.xcore_version || "" });

    // The tiles, as the old overview had them.
    tiles.players.set(`${num(m.players_online ?? 0)}${m.players_max ? ` / ${num(m.players_max)}` : ""}`);
    tiles.tps.set(tpsNow === null ? "—" : fixed(tpsNow, 1), { tone: tpsLevel ? STATE_TONE[tpsLevel] : null, alert: tpsLevel === "bad" || tpsLevel === "slow" });
    tiles.uptime.set(duration(m.uptime_seconds));
    // "3.9 / 8 GB" rather than "3.9 GB / 8 GB": the unit once, so it fits a tile.
    const sameUnit = m.memory_used >= 1024 ** 3 && m.memory_max >= 1024 ** 3;
    tiles.memory.set(
      typeof m.memory_used === "number" && typeof m.memory_max === "number"
        ? `${sameUnit ? fixed(m.memory_used / 1024 ** 3, 1) : bytes(m.memory_used)} / ${bytes(m.memory_max)}`
        : "—",
      { tone: memLevel ? STATE_TONE[memLevel] : null, alert: memLevel === "bad" }
    );
    tiles.addons.set(typeof m.addons_total === "number" ? `${num(m.addons_enabled ?? 0)} / ${num(m.addons_total)}` : num(m.modules_count ?? 0));
    if (typeof m.cache_hit_rate === "number") {
      const rate = m.cache_hit_rate;
      tiles.cache.node.hidden = false;
      tiles.cache.set(`${fixed(rate, 1)} %`, { tone: rate > 80 ? "success" : rate > 50 ? "warning" : "danger", alert: rate <= 50 });
    } else {
      tiles.cache.node.hidden = true;
    }

    // The installation.
    const rows = [
      ["xcore-version", m.xcore_version],
      ["server-software", m.server_software],
      ["java-version", m.java_version],
      ["database", m.database],
      ["worlds", m.worlds],
      ["cache-entries", typeof m.cache_size === "number" ? num(m.cache_size) : null],
      ["redis", m.redis == null ? null : t(m.redis ? "status-enabled" : "status-disabled")],
      ["cross-server", m.sync_running == null ? null : t(m.sync_running ? "status-enabled" : "status-disabled")],
      ["dashboard-sessions", m.sessions],
    ].filter((row) => row[1] !== null && row[1] !== undefined && row[1] !== "");
    swap(systemList, rows.length ? rows.map(([key, value]) => kvRow(t(key), String(value))) : emptyState(t("no-data"), "server"));
  };

  const loadRecent = () =>
    get("/api/players?offset=0&limit=5")
      .then((data) => {
        const players = (data && (data.players || data)) || [];
        if (!Array.isArray(players) || players.length === 0) {
          swap(recentList, emptyState(t("no-players-found"), "users"));
          return;
        }
        swap(
          recentList,
          players.map((player) => {
            const name = player.player_name || player.name || "";
            const last = player.last_login || player.lastLogin || player.registered;
            const isOnline = online.includes(name);
            return h(
              "div",
              { class: "recent-row" },
              playerLink(name),
              isOnline
                ? h("span", { class: "badge", data: { tone: "success" } }, t("online"))
                : last
                  ? h("time", { class: "when", title: dateTime(last), text: relative(last) })
                  : null
            );
          })
        );
      })
      .catch(() => swap(recentList, emptyState(t("could-not-load-recent-players"), "users")));

  // --- Motion ----------------------------------------------------------------------

  const motion = sceneMotion({ scene, pin, hero, beats, rail, stops, overview });
  cleanups.push(motion.stop);

  const lens = attachLens(rail);
  cleanups.push(lens);

  return {
    el,
    title: t("overview"),
    mounted() {
      motion.start();
      cleanups.push(onMetrics(fill));
      cleanups.push(wantEvery(5000));
      loadRecent();
      const timer = setInterval(() => {
        if (!document.hidden) loadRecent();
      }, 5000);
      cleanups.push(() => clearInterval(timer));
    },
    destroy() {
      cleanups.forEach((undo) => undo());
    },
  };
}

// ---------------------------------------------------------------------------
// The scene's motion
// ---------------------------------------------------------------------------

function sceneMotion({ scene, pin, hero, beats, rail, stops, overview }) {
  const pinned = window.matchMedia(PINNED_QUERY);
  const mathOk = window.CSS && CSS.supports("width", MATH);
  const byScroll = mathOk && CSS.supports("animation-timeline", "view()");
  const lag = window.matchMedia("(pointer: coarse)").matches ? LAG_TOUCH : LAG_MOUSE;

  let live = false;
  let frame = 0;
  let previous = 0;
  let snapUntil = 0;
  let top = 0;
  let step = 1;
  let target = 0;
  let current = 0;
  let aimX = 0;
  let aimY = 0;
  let leanX = 0;
  let leanY = 0;
  let active = -2;
  let heroAway = false;
  let railOn = false;

  const measure = () => {
    top = scene.getBoundingClientRect().top + window.scrollY;
    step = Math.max(1, (scene.offsetHeight - pin.offsetHeight) / BEATS);
  };

  const read = () => {
    const position = (window.scrollY - top) / step;
    target = position < 0 ? 0 : position > BEATS ? BEATS : position;
  };

  /** Which figure is on screen, for the pointer, the rail and assistive technology. */
  const mark = (position) => {
    const now = position < 0.5 ? -1 : Math.min(BEATS - 1, Math.round(position - 1));
    if (now !== active) {
      active = now;
      beats.forEach((beat, index) => beat.toggleAttribute("data-active", index === now));
      stops.forEach((stop, index) => {
        if (index === now) stop.setAttribute("aria-current", "true");
        else stop.removeAttribute("aria-current");
      });
    }
    const away = position > 0.25;
    if (away !== heroAway) {
      heroAway = away;
      hero.toggleAttribute("data-away", away);
    }
    const on = position > 0.575;
    if (on !== railOn) {
      railOn = on;
      rail.toggleAttribute("data-on", on);
    }
  };

  const write = () => {
    if (!byScroll) setVar(pin, "t", current.toFixed(4));
    setVar(pin, "lean-x", leanX.toFixed(2));
    setVar(pin, "lean-y", leanY.toFixed(2));
    mark(byScroll ? target : current);
  };

  const tick = (time) => {
    const elapsed = previous ? Math.min(64, Math.max(0, time - previous)) : 16;
    previous = time;
    const ease = 1 - Math.exp(-elapsed / lag);
    current = byScroll ? target : current + (target - current) * ease;
    leanX += (aimX - leanX) * ease * 0.5;
    leanY += (aimY - leanY) * ease * 0.5;
    const settled = Math.abs(target - current) < 0.0005 && Math.abs(aimX - leanX) < 0.01 && Math.abs(aimY - leanY) < 0.01;
    if (settled) {
      current = target;
      leanX = aimX;
      leanY = aimY;
    }
    write();
    frame = settled ? 0 : requestAnimationFrame(tick);
  };

  const wake = () => {
    if (frame || !live) return;
    previous = 0;
    frame = requestAnimationFrame(tick);
  };

  const onScroll = () => {
    read();
    if (byScroll) {
      // The scroll moves the block on its own; only the figure on screen needs knowing.
      current = target;
      mark(target);
      return;
    }
    // Arriving from elsewhere — a restored position, a jump to the far end — lands on the right
    // figure instead of spinning through the ones in between.
    if (performance.now() < snapUntil || Math.abs(target - current) > 3) current = target;
    wake();
  };

  const onResize = () => {
    if (!live) return;
    measure();
    read();
    current = target;
    write();
  };

  const onPointerMove = (event) => {
    if (event.pointerType !== "mouse") return;
    aimX = (event.clientX / window.innerWidth - 0.5) * 9;
    aimY = (event.clientY / window.innerHeight - 0.5) * -6;
    wake();
  };

  const onPointerLeave = () => {
    aimX = 0;
    aimY = 0;
    wake();
  };

  /** Scrolls to a figure by index; -1 is the opening. */
  const jump = (index, smooth) => {
    window.scrollTo({ top: Math.round(top + step * (index + 1)), behavior: smooth && !reducedMotion() ? "smooth" : "instant" });
  };

  const onClick = (event) => {
    const trigger = event.target.closest("[data-jump]");
    if (!trigger || !scene.contains(trigger)) return;
    event.preventDefault();
    if (trigger.dataset.jump === "overview") {
      overview.scrollIntoView({ behavior: reducedMotion() ? "instant" : "smooth", block: "start" });
      return;
    }
    if (live) jump(Number(trigger.dataset.jump), true);
    else overview.previousElementSibling.querySelector(".beats").scrollIntoView({ behavior: reducedMotion() ? "instant" : "smooth" });
  };

  // Tabbing reaches the links of figures that are not on screen: bring their figure up, so the
  // focus ring is never on something invisible.
  const onFocusIn = (event) => {
    if (!live) return;
    const holder = event.target.closest("[data-beat], [data-hero]");
    if (!holder) return;
    const index = holder.hasAttribute("data-hero") ? -1 : Number(holder.dataset.beat);
    if (index !== active) jump(index, false);
  };

  const resizer = new ResizeObserver(onResize);

  const begin = () => {
    live = true;
    active = -2;
    measure();
    read();
    current = target;
    snapUntil = performance.now() + 400;
    write();
    window.addEventListener("scroll", onScroll, { passive: true });
    pin.addEventListener("pointermove", onPointerMove, { passive: true });
    pin.addEventListener("pointerleave", onPointerLeave, { passive: true });
  };

  /** Hands every element back to the stylesheet's plain layout. */
  const end = () => {
    live = false;
    cancelAnimationFrame(frame);
    frame = 0;
    window.removeEventListener("scroll", onScroll);
    pin.removeEventListener("pointermove", onPointerMove);
    pin.removeEventListener("pointerleave", onPointerLeave);
    for (const name of ["t", "lean-x", "lean-y"]) setVar(pin, name, null);
    beats.forEach((beat) => beat.removeAttribute("data-active"));
    stops.forEach((stop) => stop.removeAttribute("aria-current"));
    hero.removeAttribute("data-away");
    rail.removeAttribute("data-on");
    heroAway = railOn = false;
    aimX = aimY = leanX = leanY = 0;
  };

  const sync = () => {
    const want = pinned.matches && mathOk;
    if (want === live) return;
    if (want) begin();
    else end();
  };

  return {
    start() {
      scene.addEventListener("click", onClick);
      scene.addEventListener("focusin", onFocusIn);
      resizer.observe(scene);
      pinned.addEventListener("change", sync);
      sync();
    },
    stop() {
      scene.removeEventListener("click", onClick);
      scene.removeEventListener("focusin", onFocusIn);
      resizer.disconnect();
      pinned.removeEventListener("change", sync);
      if (live) end();
    },
  };
}

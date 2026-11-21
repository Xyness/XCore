// Players: the list, and one player's profile.
//
// The profile is assembled from every module: each one that answers /api/<module>/player/<name>
// adds what it knows — sanctions, listings, logins — as a card of its own. A module that knows
// nothing about the player, or has no such route, simply adds nothing; one whose profile needs a
// permission the reader lacks is not asked at all.

import { h, icon, swap, copyText } from "../lib/dom.js";
import { get, withQuery, moduleBase } from "../lib/api.js";
import { t, num, fieldLabel, looksTemporal, relative, dateTime, date, normalizeKey } from "../lib/i18n.js";
import { allModules, markOf, moduleHref } from "../lib/modules.js";
import { latest, onMetrics } from "../lib/metrics.js";
import { listing } from "../ui/listing.js";
import { face, cell, dataTable, emptyState, errorState, badge, isPlayerColumn } from "../ui/table.js";
import { toast } from "../ui/overlay.js";

const PAGE_SIZE = 25;

function pageHead({ eyebrow, title, sub, actions }) {
  return h(
    "header",
    { class: "page-head" },
    h("div", { class: "page-heading" }, eyebrow, h("h1", { class: "page-title", text: title }), sub || null),
    actions ? h("div", { class: "page-actions" }, actions) : null
  );
}

// ---------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------

export function playersPage() {
  const sub = h("p", { class: "page-sub" });
  const list = listing({
    search: t("search-players"),
    pageSize: PAGE_SIZE,
    empty: t("no-players-found"),
    error: t("failed-to-load-players"),
    emptyIcon: "users",
    caption: t("players"),
    columns: [
      { key: "player_name", label: t("player") },
      { key: "last_login", label: t("last-seen") },
      { key: "registered", label: t("registered") },
      { key: "server_uuid", label: t("server-uuid") },
    ],
    async load({ page, search }) {
      const data = await get(withQuery("/api/players", { page, limit: PAGE_SIZE, search }));
      const rows = (data && (data.players || data)) || [];
      const total = typeof data.total === "number" ? data.total : rows.length;
      if (!search) sub.textContent = t("players-total", { count: num(total) });
      return { rows: Array.isArray(rows) ? rows : [], total };
    },
  });

  const el = h(
    "div",
    { class: "page" },
    pageHead({ eyebrow: h("p", { class: "eyebrow label", text: t("core") }), title: t("players"), sub }),
    list.el
  );

  return {
    el,
    title: t("players"),
    mounted: () => list.reload(),
    destroy: list.destroy,
  };
}

// ---------------------------------------------------------------------------
// One player
// ---------------------------------------------------------------------------

/** Symbols for a state, so a sanction is recognisable before it is read. */
const FLAG_ICONS = {
  banned: "ban",
  ip_banned: "ban",
  muted: "mic-off",
  ip_muted: "mic-off",
  warned: "triangle-alert",
  reported: "flag",
  jailed: "lock",
  frozen: "snowflake",
  watched: "eye",
  premium: "star",
  two_factor_enabled: "key-round",
};

/** States that are good news when on. Everything else that is on is a sanction or a warning. */
const GOOD_FLAGS = new Set(["premium", "two_factor_enabled", "registered", "verified", "online"]);

const COUNTER = /(_count$|^total_|_total$)/;

/** How many rows of a list a profile card shows before "Show all". */
const LIST_GLANCE = 6;

/**
 * One module's view of a player.
 *
 * A flat list of thirty fields, half of them "false", hides the one that matters. The payload is
 * split by what each field is worth: states that are on come first, as badges; tallies that are
 * not zero become figures; the descriptive fields follow as a list; and every list inside it is a
 * table of its own. A state that is off and a counter at zero are left out — their absence is the
 * information.
 */
/** The player's own identity, which the head of the profile already shows. */
const IDENTITY = new Set(["uuid", "player_uuid", "server_uuid", "mojang_uuid"]);

function moduleFacts(data, name) {
  const out = [];
  if (Array.isArray(data)) {
    if (data.length) out.push(dataTable(data));
    return out;
  }
  const flags = [];
  const counters = [];
  const rest = [];
  const lists = [];
  for (const key in data) {
    const value = data[key];
    if (Array.isArray(value)) lists.push([key, value]);
    else if (typeof value === "boolean") flags.push([key, value]);
    else if (typeof value === "number" && COUNTER.test(key) && !looksTemporal(key)) counters.push([key, value]);
    else if (value !== null && value !== undefined && value !== "" && typeof value !== "object") {
      // Each module repeats whose payload it is: the name and the UUIDs are already at the top.
      if (IDENTITY.has(normalizeKey(key))) continue;
      if (isPlayerColumn(key) && String(value).toLowerCase() === name.toLowerCase()) continue;
      rest.push([key, value]);
    }
  }

  // "Nothing active" is good news only where something could have been: a module whose states
  // are all good ones (premium, 2FA) and has none of them on simply shows none.
  const watched = flags.some(([key]) => !GOOD_FLAGS.has(key));
  if (flags.length && (watched || flags.some(([, value]) => value))) {
    const on = flags.filter(([, value]) => value);
    out.push(
      h(
        "div",
        { class: "badges profile-flags" },
        on.length === 0
          ? badge(t("nothing-active"), "success", "check")
          : on.map(([key]) => badge(fieldLabel(key), GOOD_FLAGS.has(key) ? "success" : "danger", FLAG_ICONS[key] || null))
      )
    );
  }

  const nonZero = counters.filter(([, value]) => value > 0);
  if (nonZero.length) {
    out.push(
      h(
        "div",
        { class: "tiles tiles-compact" },
        nonZero.map(([key, value]) =>
          h(
            "div",
            { class: "tile", data: { tone: /warn|ban|mute|kick|report|strike|jail|sanction/.test(key) ? "warning" : null } },
            h("p", { class: "tile-label label" }, h("span", { class: "tile-dot" }), h("span", { text: fieldLabel(key) })),
            h("p", { class: "tile-value", text: num(value) })
          )
        )
      )
    );
  }

  if (rest.length) {
    out.push(
      h(
        "div",
        { class: "kv" },
        rest.map(([key, value]) =>
          h("div", { class: "kv-row" }, h("span", { class: "kv-key", text: fieldLabel(key) }), h("span", { class: "kv-value" }, cell(key, value, false)))
        )
      )
    );
  }

  // A list is a glance here, not the whole record: its first rows, and the rest on demand.
  for (const [key, rows] of lists) {
    if (!rows.length || typeof rows[0] !== "object") continue;
    const box = h("div", { class: "profile-list" }, h("h4", { class: "label profile-list-title" }, fieldLabel(key), h("span", { class: "profile-list-count num", text: num(rows.length) })));
    const shown = rows.length > LIST_GLANCE ? rows.slice(0, LIST_GLANCE) : rows;
    let table = dataTable(shown);
    box.append(table);
    if (rows.length > LIST_GLANCE) {
      const more = h("button", { class: "btn btn-xs btn-quiet profile-more", type: "button" }, t("show-all", { count: num(rows.length) }), icon("chevron-down"));
      more.addEventListener("click", () => {
        const full = dataTable(rows);
        table.replaceWith(full);
        table = full;
        more.remove();
      });
      box.append(more);
    }
    out.push(box);
  }

  return out;
}

/** Whether a module's answer says anything at all. */
function hasContent(data) {
  if (Array.isArray(data)) return data.length > 0;
  if (!data || typeof data !== "object") return false;
  for (const key in data) {
    const value = data[key];
    if (Array.isArray(value)) {
      if (value.length) return true;
    } else if (value !== null && value !== "" && value !== undefined) return true;
  }
  return false;
}

function idRow(label, value) {
  const button = h(
    "button",
    {
      class: "id-value",
      type: "button",
      title: t("click-to-copy"),
      onclick: async () => {
        const done = await copyText(value);
        toast(done ? t("copied") : t("copy-failed"), done ? "success" : "error");
      },
    },
    h("span", { class: "mono", text: value }),
    icon("copy")
  );
  return h("div", { class: "id-row" }, h("span", { class: "label", text: label }), button);
}

export function profilePage(name) {
  const cleanups = [];
  const status = h("span", { class: "badge", text: t("offline") });
  const ids = h("div", { class: "id-list" });
  const facts = h("dl", { class: "profile-facts" });
  const modulesBox = h("div", { class: "profile-modules" });
  const waiting = h("p", { class: "profile-waiting label" }, h("span", { class: "spin", "aria-hidden": "true" }), h("span", { text: t("loading") }));

  const head = h(
    "header",
    { class: "profile-head" },
    face(name, 96),
    h(
      "div",
      { class: "profile-heading" },
      h("p", { class: "eyebrow label", text: t("player-profile") }),
      h("div", { class: "profile-name-row" }, h("h1", { class: "page-title", text: name }), status),
      ids
    ),
    facts
  );

  const el = h("div", { class: "page" }, head, waiting, modulesBox);

  const setOnline = (metrics) => {
    const online = Array.isArray(metrics.online_players) && metrics.online_players.some((entry) => entry.toLowerCase() === name.toLowerCase());
    status.textContent = t(online ? "online" : "offline");
    if (online) status.dataset.tone = "success";
    else delete status.dataset.tone;
  };

  const fact = (label, value, title) =>
    h("div", { class: "profile-fact" }, h("dt", { class: "label", text: label }), h("dd", { title: title || null, text: value }));

  const loadIdentity = () =>
    // The endpoint searches by name, so a player outside the first page is still found.
    get(withQuery("/api/players", { search: name, limit: 25 }))
      .then((data) => {
        const players = (data && (data.players || data)) || [];
        const player = players.find((entry) => String(entry.player_name || entry.name || "").toLowerCase() === name.toLowerCase());
        if (!player) {
          swap(ids, h("p", { class: "page-sub", text: t("player-not-in-database") }));
          return;
        }
        swap(
          ids,
          player.server_uuid || player.uuid ? idRow(t("server-uuid"), player.server_uuid || player.uuid) : null,
          player.mojang_uuid ? idRow(t("mojang-uuid"), player.mojang_uuid) : null
        );
        const last = player.last_login || player.lastLogin;
        const registered = player.registered || player.first_login || player.created_at;
        swap(
          facts,
          fact(t("last-login"), last ? relative(last) : t("never"), last ? dateTime(last) : null),
          registered ? fact(t("registered"), date(registered), dateTime(registered)) : null
        );
      })
      .catch(() => swap(ids, h("p", { class: "page-sub", text: t("failed-to-load-data") })));

  const loadModules = () => {
    // A module whose profile the reader may not read — addresses, accounts — is not asked: the
    // panel would only refuse it.
    const mods = allModules().filter((mod) => mod.profile !== false);
    let pending = mods.length;
    let found = 0;
    const settle = () => {
      pending -= 1;
      if (pending > 0) return;
      waiting.remove();
      if (found === 0) modulesBox.append(h("section", { class: "card" }, emptyState(t("no-module-data"), "user")));
    };
    if (pending === 0) {
      pending = 1;
      settle();
      return;
    }
    // Each module answers on its own time; its card goes in its place in the module order, so the
    // page does not reshuffle as the answers land.
    const slots = mods.map(() => h("div", { class: "profile-slot" }));
    modulesBox.append(...slots);
    mods.forEach((mod, index) => {
      get(`${moduleBase(mod.name)}/player/${encodeURIComponent(name)}`)
        .then((data) => {
          if (!hasContent(data)) return;
          found += 1;
          const { mark, icon: iconName } = markOf(mod);
          swap(
            slots[index],
            h(
              "section",
              { class: "card profile-card enter", vars: { mark } },
              h(
                "div",
                { class: "card-head" },
                h("h2", { class: "card-title" }, h("span", { class: "mark-tile" }, icon(iconName)), mod.name),
                h("a", { class: "btn btn-xs btn-quiet", href: moduleHref(mod) }, t("open"), icon("arrow-right"))
              ),
              moduleFacts(data, name)
            )
          );
        })
        .catch(() => {})
        .finally(settle);
    });
  };

  return {
    el,
    title: name,
    mounted() {
      if (latest()) setOnline(latest());
      cleanups.push(onMetrics(setOnline));
      loadIdentity();
      loadModules();
    },
    destroy() {
      cleanups.forEach((undo) => undo());
    },
  };
}

export { pageHead, errorState };

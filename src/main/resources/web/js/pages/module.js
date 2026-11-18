// A module's pages, built from what it described in Java.
//
// A module declares each page with a WebPageSpec and /api/modules ships the description: a page
// of statistic tiles, a table, or the configuration editor, with whatever goes with it — row
// actions, a creation form, a page-wide button, a search box, filters, paging, charts, a heat
// map. Everything below renders that description; no endpoint, label or form of any particular
// addon appears in this file.

import { h, icon, swap, balanceTiles } from "../lib/dom.js";
import { get, post, withQuery, nodeList } from "../lib/api.js";
import { t, num, fixed, bytes, fieldLabel } from "../lib/i18n.js";
import { markOf, kindLabel, pageIcon, pageName, pageHref, pageTitle } from "../lib/modules.js";
import { listing, rowsOf, pick } from "../ui/listing.js";
import { cell, emptyState, errorState } from "../ui/table.js";
import { openDialog, toast } from "../ui/overlay.js";
import { pageHead } from "./players.js";

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

function moduleEyebrow(mod) {
  const { mark, icon: iconName } = markOf(mod);
  return h("p", { class: "eyebrow label", vars: { mark } }, icon(iconName), h("a", { href: `#/m/${encodeURIComponent(mod.name)}`, text: mod.name }));
}

/** A spec's colour name ("red", "green"…) as a tone the stylesheet knows. */
const TONES = { red: "red", orange: "orange", yellow: "yellow", green: "green", blue: "blue", purple: "purple", cyan: "cyan" };
const toneOf = (color) => TONES[String(color || "").toLowerCase()] || null;

/** Applies a tile's declared format to a raw value. */
function formatStat(value, format) {
  if (typeof value !== "number") return String(value);
  if (format === "megabytes") return bytes(value);
  if (format === "decimal") return fixed(value, 1);
  if (format === "number") return num(value);
  return Number.isInteger(value) ? num(value) : fixed(value, 1);
}

/**
 * A tile's colour, a crossed threshold overriding the default. The tightest bound wins, so the
 * order they were declared in does not matter.
 */
function statTone(tile, value) {
  if (typeof value === "number" && Array.isArray(tile.thresholds)) {
    const crossed = tile.thresholds.filter((bound) => value < bound.below).sort((a, b) => a.below - b.below);
    if (crossed.length) return { tone: toneOf(crossed[0].color), alert: true };
  }
  return { tone: toneOf(tile.color), alert: false };
}

function statTiles(spec, data) {
  const shown = new Set();
  const nodes = [];
  for (const tile of spec.stats || []) {
    const value = pick(data, tile.keys);
    if (value === null) continue;
    (tile.keys || []).forEach((key) => shown.add(key));
    const { tone, alert } = statTone(tile, value);
    nodes.push(
      h(
        "div",
        { class: "tile enter", data: { tone, alert: alert || null }, vars: { i: nodes.length } },
        h("p", { class: "tile-label label" }, h("span", { class: "tile-dot" }), h("span", { text: t(tile.labelKey) })),
        h("p", { class: "tile-value", text: formatStat(value, tile.format) })
      )
    );
  }
  return { nodes, shown };
}

const tileSkeleton = (count) =>
  Array.from({ length: Math.max(2, Math.min(count, 8)) }, () =>
    h("div", { class: "tile", "aria-hidden": "true" }, h("span", { class: "skel", vars: { w: "50%" } }), h("span", { class: "skel skel-lg" }))
  );

/**
 * Builds a request body by reading each declared parameter off a row. A source written "=value"
 * is a constant rather than a field name: it is what lets one endpoint back two buttons that
 * differ only by a flag.
 */
function bodyFor(params, row) {
  const body = {};
  for (const param of params || []) {
    const from = param.from || [];
    if (from.length === 1 && typeof from[0] === "string" && from[0].charAt(0) === "=") {
      body[param.param] = from[0].slice(1);
      continue;
    }
    const value = pick(row, from);
    if (value !== null) body[param.param] = value;
  }
  return body;
}

/** A POST a page declared, with the server's own words when it refuses. */
async function send(endpoint, body) {
  try {
    return await post(endpoint, body);
  } catch (error) {
    throw new Error(error.message || t("action-failed"));
  }
}

const STYLE_TONE = { danger: "danger", success: "success", warning: "warning" };

// ---------------------------------------------------------------------------
// A module's home
// ---------------------------------------------------------------------------

export function moduleHome(mod) {
  const { mark, icon: iconName } = markOf(mod);
  const pages = Array.isArray(mod.pages) ? mod.pages : [];

  // Its own statistics when it has a stats page: a home that only lists links tells the reader
  // nothing the sidebar does not.
  const statsPage = pages.find((page) => page.spec && page.spec.type === "stats" && Array.isArray(page.spec.stats) && page.spec.stats.length);
  const tiles = statsPage ? h("div", { class: "tiles", "aria-busy": "true" }, tileSkeleton(statsPage.spec.stats.length)) : null;

  const cards = h(
    "div",
    { class: "page-cards" },
    pages.map((page, index) =>
      h(
        "a",
        { class: "card page-card enter", href: pageHref(mod, page), vars: { mark, i: index } },
        h("span", { class: "mark-tile" }, icon(pageIcon(page))),
        h("span", { class: "page-card-text" }, h("span", { class: "page-card-name", text: pageName(page) }), h("span", { class: "page-card-kind", text: kindLabel(page) })),
        icon("arrow-right", "icon-go")
      )
    )
  );

  const el = h(
    "div",
    { class: "page" },
    h(
      "header",
      { class: "page-head module-head" },
      h(
        "div",
        { class: "page-heading module-heading", vars: { mark } },
        h("span", { class: "mark-tile mark-tile-lg" }, icon(iconName)),
        h("div", null, h("p", { class: "eyebrow label", text: t("plugins-title") }), h("h1", { class: "page-title", text: mod.name }))
      )
    ),
    tiles,
    h("h2", { class: "section-title", text: t("pages") }),
    pages.length ? cards : h("section", { class: "card" }, emptyState(t("no-data"), "list"))
  );

  return {
    el,
    title: mod.name,
    mounted() {
      if (!statsPage) return;
      get(statsPage.spec.endpoint)
        .then((data) => {
          const { nodes } = statTiles(statsPage.spec, data || {});
          if (nodes.length) {
            swap(tiles, nodes);
            balanceTiles(tiles);
            tiles.removeAttribute("aria-busy");
          } else tiles.remove();
        })
        .catch(() => tiles.remove());
    },
  };
}

// ---------------------------------------------------------------------------
// A page that describes itself
// ---------------------------------------------------------------------------

export function specPage(mod, page) {
  const spec = page.spec;
  if (spec.type === "config") return configPage(mod, page);
  if (spec.type === "stats") return statsPage(mod, page);
  return tablePage(mod, page);
}

/**
 * Whether the reader may only read a configuration page: the panel marks it so when saving needs a
 * permission they lack, and names that permission.
 */
function readOnlyOf(page) {
  if (!page || page.readOnly !== true) return null;
  return { needs: Array.isArray(page.needs) ? page.needs.filter((node) => typeof node === "string") : [] };
}

function configPage(mod, page) {
  const holder = h("div", { class: "config-holder" }, h("section", { class: "card" }, h("div", { class: "skel-rows" }, h("span", { class: "skel" }), h("span", { class: "skel" }), h("span", { class: "skel" }))));
  let editor = null;
  const el = h("div", { class: "page" }, pageHead({ eyebrow: moduleEyebrow(mod), title: pageTitle(mod, page) }), holder);
  return {
    el,
    async mounted() {
      // The editor is the heaviest page and the least visited: it is loaded when opened.
      const { configEditor } = await import("./config.js");
      if (!holder.isConnected) return;
      editor = configEditor(mod.name, readOnlyOf(page));
      swap(holder, editor.el);
    },
    destroy() {
      if (editor && editor.destroy) editor.destroy();
    },
  };
}

function statsPage(mod, page) {
  const spec = page.spec;
  const tiles = h("div", { class: "tiles", "aria-busy": "true" }, tileSkeleton((spec.stats || []).length || 4));
  const below = h("div", { class: "stack section" });
  const refresh = h(
    "button",
    { class: "btn btn-sm btn-secondary glass glass-set", type: "button", onclick: () => load(true) },
    icon("refresh-cw"),
    t("refresh")
  );

  const el = h(
    "div",
    { class: "page", vars: { mark: markOf(mod).mark } },
    pageHead({ eyebrow: moduleEyebrow(mod), title: pageTitle(mod, page), actions: [refresh] }),
    tiles,
    below
  );

  const togglesCard = spec.toggles ? h("section", { class: "card" }, h("div", { class: "card-head" }, h("h2", { class: "card-title", text: t(spec.toggles.titleKey) }))) : null;
  const detailsCard = spec.details ? h("section", { class: "card", hidden: true }, h("div", { class: "card-head" }, h("h2", { class: "card-title", text: t(spec.details.titleKey) }))) : null;
  const charts = (spec.charts || (spec.chart ? [spec.chart] : [])).map((chart) => ({
    chart,
    card: h("section", { class: "card chart-card" }, h("div", { class: "card-head" }, h("h2", { class: "card-title", text: t(chart.titleKey) })), h("div", { class: "chart-slot" }, h("span", { class: "skel skel-chart" }))),
  }));
  const heatCard = spec.heatmap
    ? h("section", { class: "card heat-card" }, h("div", { class: "card-head" }, h("h2", { class: "card-title", text: t(spec.heatmap.titleKey) })), h("div", { class: "chart-slot" }, h("span", { class: "skel skel-chart" })))
    : null;

  const grid = h("div", { class: "grid-2" }, togglesCard, detailsCard);
  below.append(...charts.map((entry) => entry.card), heatCard || "", grid);

  const fillBody = (card, ...nodes) => {
    const head = card.querySelector(".card-head");
    swap(card, head, ...nodes);
  };

  // A payload that lists worlds and names the one it describes can be asked for another
  // (?world=): the heat map offers the switch, and the whole page follows it.
  let world = "";

  async function load(again = false) {
    if (again) refresh.disabled = true;
    try {
      const data = (await get(withQuery(spec.endpoint, { world: world || null }))) || {};
      const { nodes, shown } = statTiles(spec, data);
      swap(tiles, nodes.length ? nodes : emptyState(t(spec.emptyKey || "no-data"), "chart-column"));
      balanceTiles(tiles);
      tiles.removeAttribute("aria-busy");

      if (togglesCard) {
        const source = spec.toggles.from ? data[spec.toggles.from] || {} : data;
        if (spec.toggles.from) shown.add(spec.toggles.from);
        const keys = Object.keys(source).filter((key) => typeof source[key] === "boolean");
        keys.forEach((key) => shown.add(key));
        fillBody(
          togglesCard,
          keys.length
            ? h(
                "div",
                { class: "kv" },
                keys.map((key) =>
                  h(
                    "div",
                    { class: "kv-row" },
                    h("span", { class: "kv-key", text: fieldLabel(key) }),
                    h("span", { class: "badge", data: { tone: source[key] ? "success" : "neutral" } }, icon(source[key] ? "check" : "x"), t(source[key] ? "status-enabled" : "status-disabled"))
                  )
                )
              )
            : emptyState(t(spec.toggles.emptyKey), "list")
        );
      }

      if (detailsCard) {
        // A state outside the toggles (a top-level flag, when the toggles read a nested object)
        // would otherwise be shown nowhere; it reads here as yes or no.
        const rest = Object.keys(data).filter((key) => !shown.has(key) && data[key] !== null && typeof data[key] !== "object");
        detailsCard.hidden = rest.length === 0;
        fillBody(
          detailsCard,
          h(
            "div",
            { class: "kv" },
            rest.map((key) => h("div", { class: "kv-row" }, h("span", { class: "kv-key", text: fieldLabel(key) }), h("span", { class: "kv-value" }, cell(key, data[key], false))))
          )
        );
      }
      grid.hidden = !(togglesCard || (detailsCard && !detailsCard.hidden));

      if (charts.length || heatCard) {
        const { barChart, heatmap } = await import("./charts.js");
        for (const { chart, card } of charts) {
          const source = chart.endpoint && chart.endpoint !== spec.endpoint ? await get(chart.endpoint).catch(() => null) : data;
          const { rows } = rowsOf(source, { dataKeys: chart.dataKey ? [chart.dataKey] : null });
          const slot = card.querySelector(".chart-slot");
          if (!source || rows.length === 0) swap(slot, emptyState(t(chart.emptyKey || "no-chart-data-available"), "chart-column"));
          else swap(slot, barChart(rows, chart));
        }
        if (heatCard) {
          const conf = spec.heatmap;
          const source = conf.endpoint && conf.endpoint !== spec.endpoint ? await get(conf.endpoint).catch(() => null) : data;
          const { rows } = rowsOf(source, { dataKeys: conf.dataKey ? [conf.dataKey] : null });
          const slot = heatCard.querySelector(".chart-slot");
          const worlds = source && Array.isArray(source.worlds) && source.worlds.every((name) => typeof name === "string") ? source.worlds : null;
          const choice = {
            worlds,
            world: source && typeof source.world === "string" ? source.world : "",
            onWorld: (name) => {
              world = name;
              load(true);
            },
          };
          if (!source || rows.length === 0) {
            swap(slot, worlds && worlds.length > 1 ? heatmap([], conf, choice) : emptyState(t(conf.emptyKey || "no-chart-data-available"), "map"));
          } else swap(slot, heatmap(rows, conf, choice));
        }
      }
    } catch (error) {
      swap(tiles, h("div", { class: "card tiles-error" }, errorState(t(spec.errorKey || "failed-to-load-data"), () => load(true))));
      tiles.removeAttribute("aria-busy");
    } finally {
      refresh.disabled = false;
    }
  }

  return { el, mounted: () => load() };
}

function tablePage(mod, page) {
  const spec = page.spec;
  const title = pageTitle(mod, page);
  const actions = [];

  const rowActions = (spec.actions || []).map((action) => {
    const label = t(action.labelKey);
    return {
      label,
      tone: STYLE_TONE[action.style] || null,
      run: async (row) => {
        const target = pick(row, action.idFrom) ?? "";
        // The question is the title — "Unban SlimeGolem?" — with nothing to repeat under it.
        const done = await openDialog({
          title: target !== "" ? t("confirm-action-target", { action: label, target }) : t("confirm-action"),
          confirmLabel: label,
          tone: action.style === "danger" ? "danger" : "",
          onConfirm: () => send(action.endpoint, bodyFor(action.body, row)),
        });
        if (done === null) return;
        toast(t("action-completed-successfully"), "success");
        if (action.reload !== false) list.reload();
      },
    };
  });

  if (spec.bulk) {
    const label = t(spec.bulk.labelKey);
    actions.push(
      h(
        "button",
        {
          class: "btn btn-secondary glass glass-set",
          type: "button",
          data: { tone: STYLE_TONE[spec.bulk.style] || null },
          onclick: async () => {
            const done = await openDialog({
              title: label,
              text: t(spec.bulk.confirmKey),
              confirmLabel: label,
              tone: spec.bulk.style === "danger" ? "danger" : "",
              onConfirm: () => send(spec.bulk.endpoint, {}),
            });
            if (done === null) return;
            toast(t("action-completed-successfully"), "success");
            list.reload();
          },
        },
        icon(spec.bulk.style === "danger" ? "trash-2" : "refresh-cw"),
        label
      )
    );
  }

  if (spec.form) {
    const label = t(spec.form.titleKey);
    const fields = (spec.form.fields || []).map((field) => ({
      key: field.key,
      label: t(field.labelKey),
      type: field.type || "text",
      placeholder: field.placeholderKey ? t(field.placeholderKey) : "",
      required: !!field.required,
    }));
    actions.push(
      h(
        "button",
        {
          class: "btn glass-brass",
          type: "button",
          onclick: async () => {
            const values = await openDialog({
              title: label,
              fields,
              confirmLabel: label,
              tone: spec.form.style === "danger" ? "danger" : "",
              onConfirm: (body) => send(spec.form.endpoint, body),
            });
            if (values === null) return;
            toast(t("action-completed-successfully"), "success");
            list.reload();
          },
        },
        icon("plus"),
        label
      )
    );
  }

  const list = listing({
    search: spec.searchKey ? t(spec.searchKey) : spec.filters && spec.filters.length ? t("search") : null,
    filters: (spec.filters || []).map((filter) => ({
      param: filter.param,
      placeholder: t(filter.placeholderKey),
      options: (filter.options || []).map((option) => ({ value: option.value, label: t(option.labelKey) })),
    })),
    pageSize: spec.pageSize || 0,
    actions: rowActions,
    empty: t(spec.emptyKey || "no-data-found"),
    error: t(spec.errorKey || "failed-to-load-data"),
    caption: title,
    async load({ page: number, search, filters }) {
      const url = withQuery(spec.endpoint, {
        page: spec.pageSize ? number : null,
        limit: spec.pageSize || null,
        search,
        ...filters,
      });
      const data = await get(url);
      return rowsOf(data, { dataKeys: spec.dataKeys, totalKeys: spec.totalKeys, pageSize: spec.pageSize, page: number });
    },
  });

  // "/" goes to the search box, as it does on most sites with one.
  const onKey = (event) => {
    if (event.key !== "/" || event.target.closest("input, textarea, select, [contenteditable]")) return;
    event.preventDefault();
    list.focusSearch();
  };
  document.addEventListener("keydown", onKey);

  const el = h("div", { class: "page" }, pageHead({ eyebrow: moduleEyebrow(mod), title, actions }), list.el);

  return {
    el,
    mounted: () => list.reload(),
    destroy() {
      document.removeEventListener("keydown", onKey);
      list.destroy();
    },
  };
}

/**
 * A sentence with a permission node in it, the node set as code — as the login screen sets the
 * command. The translation places it with {permission}.
 */
function withNode(key, nodes) {
  const text = t(key);
  const at = text.indexOf("{permission}");
  const code = h("code", { text: nodeList(nodes) });
  if (at < 0) return h("p", null, text, " ", code);
  return h("p", null, text.slice(0, at), code, text.slice(at + "{permission}".length));
}

/**
 * A page this session may not open — a module, the players or the configuration editor. Said
 * calmly, with the permission that would open it, so the reader can ask for it by name.
 *
 * @param {object} options
 * @param {object|null} [options.mod]  The module, when the page is one of a module's.
 * @param {string[]} [options.needs]   The permission that opens it, or its alternatives.
 */
export function noAccessPage({ mod = null, needs = [] } = {}) {
  let eyebrow = h("p", { class: "eyebrow label", text: t("xcore") });
  if (mod) {
    const { mark, icon: iconName } = markOf(mod);
    eyebrow = h("p", { class: "eyebrow label", vars: { mark } }, icon(iconName), h("span", { text: mod.name }));
  }
  const el = h(
    "div",
    { class: "page" },
    pageHead({ eyebrow, title: t("no-access") }),
    h(
      "section",
      { class: "card" },
      h(
        "div",
        { class: "state state-locked" },
        h("span", { class: "state-icon" }, icon("lock")),
        h("p", { text: t("no-access-hint") }),
        needs.length ? withNode("no-access-needs", needs) : null,
        h("p", { class: "state-note", text: t("no-access-later") })
      ),
      h("div", { class: "state-actions" }, h("a", { class: "btn btn-secondary glass glass-set", href: "#/" }, icon("arrow-left"), t("overview")))
    )
  );
  return { el, title: t("no-access"), href: "", module: mod ? mod.name : null, mark: mod ? markOf(mod).mark : "" };
}

/** A page or a module that is not there (any more): an addon was removed, a link is old. */
export function missingPage(mod = null) {
  const el = h(
    "div",
    { class: "page" },
    pageHead({ eyebrow: mod ? moduleEyebrow(mod) : h("p", { class: "eyebrow label", text: t("xcore") }), title: t("page-not-found") }),
    h(
      "section",
      { class: "card" },
      emptyState(t("page-not-found-hint"), "puzzle"),
      h("div", { class: "state-actions" }, h("a", { class: "btn btn-secondary glass glass-set", href: "#/" }, icon("arrow-left"), t("overview")))
    )
  );
  return { el, title: t("page-not-found"), href: "" };
}

export { moduleEyebrow, bodyFor, send, readOnlyOf, withNode };

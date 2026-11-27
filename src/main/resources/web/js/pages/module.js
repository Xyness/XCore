// A module's pages, built from what it described in Java.
//
// A module declares each page with a WebPageSpec and /api/modules ships the description: a page
// of statistic tiles, a table, a grid of cells or the configuration editor, with whatever goes
// with it — row actions that confirm, open a form or draw a chart, a creation form, page-wide
// buttons, a search box, filters, paging, charts, lists, a heat map. Everything below renders
// that description; no endpoint, label or form of any particular addon appears in this file.

import { h, icon, swap, balanceTiles, saveFile } from "../lib/dom.js";
import { attachLens } from "../lib/glass.js";
import { get, post, download, withQuery } from "../lib/api.js";
import { requirements } from "../lib/access.js";
import { t, num, fixed, bytes, fieldLabel } from "../lib/i18n.js";
import { markOf, iconFor, kindLabel, pageIcon, pageName, pageHref, pageTitle } from "../lib/modules.js";
import { listing, filterSelect, rowsOf, pick } from "../ui/listing.js";
import { cell, dataTable, emptyState, errorState, skeletonRows, isMaterialKey, materialLabel } from "../ui/table.js";
import { loadOptions } from "../ui/fields.js";
import { openDialog, openPane, toast } from "../ui/overlay.js";
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

/**
 * Whether a row keeps a button that only some rows have (Action#when): "stop" on what is running,
 * "restore" on what was removed. Compared as text, as the page declared the values.
 */
function applies(action, row) {
  const rule = action.when;
  if (!rule || !rule.field) return true;
  const held = row ? row[rule.field] : null;
  return (rule.values || []).map(String).includes(held === null || held === undefined ? "" : String(held));
}

/**
 * A form's inputs as openDialog takes them. Opened from a row, each starts on what the row
 * holds — the first of the row fields it names (from), or the one of its own key — and otherwise
 * on its declared value.
 */
function fieldsOf(fields, row = null) {
  return (fields || []).map((field) => {
    const held = row ? pick(row, field.from && field.from.length ? field.from : [field.key]) : null;
    return {
      key: field.key,
      label: t(field.labelKey),
      type: field.type || "text",
      placeholder: field.placeholderKey ? t(field.placeholderKey) : "",
      required: !!field.required,
      help: field.helpKey ? t(field.helpKey) : "",
      value: held !== null && held !== "" ? held : field.value ?? "",
      min: field.min,
      max: field.max,
      step: field.step,
      options: (field.options || []).map((option) => ({ value: String(option.value), label: t(option.labelKey) })),
      optionsFrom: field.optionsFrom || null,
    };
  });
}

/**
 * The columns a table page names (WebPageSpec#columns), as a table takes them; null for a page
 * that names none, whose every field is a column. "item" is the item column.
 */
function namedColumns(spec) {
  const named = Array.isArray(spec.columns) ? spec.columns.filter((key) => typeof key === "string" && key) : [];
  return named.length ? named.map((key) => ({ key, label: key === "item" ? t("item") : fieldLabel(key) })) : null;
}

/** A page's filters as a toolbar takes them: the choices it knows, and how to ask for the others. */
const filtersOf = (spec) =>
  (spec.filters || []).map((filter) => ({
    param: filter.param,
    placeholder: t(filter.placeholderKey),
    options: (filter.options || []).map((option) => ({ value: option.value, label: t(option.labelKey) })),
    load: filter.optionsFrom && filter.optionsFrom.endpoint ? () => loadOptions(filter.optionsFrom) : null,
  }));

/**
 * What a row is about, for the heading of what its button opens — "Unban SlimeGolem?", "Edit
 * Stone": the first of the fields the action names that the row has. A material reads as the
 * game names the item.
 */
function targetOf(action, row, fallback = "") {
  for (const key of action.idFrom || []) {
    const value = row ? row[key] : null;
    if (value === null || value === undefined || value === "") continue;
    return isMaterialKey(key) && typeof value === "string" ? materialLabel(value) : value;
  }
  return fallback;
}

/** A moment sent as text — "1764187200000" — is the number it reads as, so a chart can date it. */
const asMoment = (value) => (typeof value === "string" && /^\d{10,13}$/.test(value) ? Number(value) : value);

/** Draws the series a "show me" button asks for, in a pane of its own. */
function showChart({ title, sub = "", url, chart, mark }) {
  return openPane({
    title,
    sub,
    wide: true,
    mark,
    build() {
      const slot = h("div", { class: "chart-slot" }, h("span", { class: "skel skel-chart" }));
      const load = async () => {
        swap(slot, h("span", { class: "skel skel-chart" }));
        try {
          const [data, { barChart }] = await Promise.all([get(url), import("./charts.js")]);
          const { rows } = rowsOf(data, {});
          if (rows.length === 0) return swap(slot, emptyState(t("no-chart-data-available"), "chart-column"));
          const series = rows.map((row) => ({ ...row, [chart.labelField]: asMoment(row[chart.labelField]) }));
          swap(slot, barChart(series, { labelField: chart.labelField, valueField: chart.valueField, titleKey: title, maxPoints: 0 }));
        } catch (error) {
          swap(slot, errorState((error && error.message) || t("failed-to-load-data"), load));
        }
      };
      load();
      return h("div", { class: "pane-chart" }, slot);
    },
  });
}

/**
 * Does what a row's button is for, wherever the row is drawn: a line of a table, a cell of a
 * grid, the container a grid shows. A plain action asks "are you sure?"; one with fields opens
 * them, filled in from the row, and sends them with its declared parameters — every field, an
 * empty one as "", so the endpoint can tell "cleared" from "not asked"; a chart action reads its
 * endpoint and draws the answer.
 *
 * @param {object} action The action, as the page declared it.
 * @param {object} row    What it acts on.
 * @param {object} [where]
 * @param {object} [where.context] What the page adds to every request — a grid's container; a
 *   parameter the action declares under the same name wins.
 * @param {string} [where.target]  What the row is about, when the action names no field for it.
 * @param {string} [where.mark]    The module's colour, for what the action draws.
 * @returns {Promise<boolean>} Whether something was sent and accepted.
 */
async function runAction(action, row, { context = {}, target = "", mark = null } = {}) {
  const label = t(action.labelKey);
  const about = targetOf(action, row, target);
  const declared = { ...context, ...bodyFor(action.body, row) };
  const asks = Array.isArray(action.fields) && action.fields.length > 0;

  let title;
  let sub = "";
  if (action.titleKey) {
    title = t(action.titleKey, { action: label, target: about });
    // A heading of its own that does not say which row ("Edit the product") is followed by it.
    if (about !== "" && !/\{target\}|%target%/.test(t(action.titleKey))) sub = String(about);
  } else if (action.kind === "chart" || asks) title = about !== "" ? t("action-target", { action: label, target: about }) : label;
  // The question is the title — "Unban SlimeGolem?" — with nothing to repeat under it.
  else title = about !== "" ? t("confirm-action-target", { action: label, target: about }) : t("confirm-action");

  if (action.kind === "chart") {
    await showChart({ title, sub, url: withQuery(action.endpoint, declared), chart: action.chart || {}, mark });
    return false;
  }

  const done = await openDialog({
    title,
    text: sub,
    fields: fieldsOf(action.fields, row),
    confirmLabel: label,
    tone: action.style === "danger" ? "danger" : "",
    sendEmpty: true,
    onConfirm: (values) => send(action.endpoint, { ...declared, ...values }),
  });
  if (done === null) return false;
  toast(t("action-completed-successfully"), "success");
  return true;
}

/**
 * The buttons a page declares for itself as a whole (WebPageSpec.Button): each asks first — its
 * sentence, its fields — then posts, or fetches a file to save when it is a download.
 *
 * @param {object} spec The page.
 * @param {object} [page]
 * @param {() => object} [page.query]   What the reader is looking at — the search, the filters —
 *   which a download carries in its query string.
 * @param {() => object} [page.context] What the page adds to every request: a grid's container. A
 *   list of choices named like one of its parameters starts on it ("which shop": the one shown).
 * @param {() => void} [page.done]      Called once a button changed something.
 */
function pageButtons(spec, { query = () => ({}), context = () => ({}), done = () => {} } = {}) {
  /** A button's fields; a list of choices it declared no value for starts on what the page is showing. */
  const fieldsFor = (button) => {
    const shown = context();
    return fieldsOf(button.fields).map((field) =>
      field.type === "select" && field.value === "" && shown[field.key] !== undefined ? { ...field, value: shown[field.key] } : field
    );
  };

  return (spec.buttons || []).map((button) => {
    const label = t(button.labelKey);
    const reads = button.kind === "download";
    const asks = Array.isArray(button.fields) && button.fields.length > 0;
    const glyph = iconFor(button.icon, reads ? "download" : button.style === "danger" ? "trash-2" : asks ? "sliders-horizontal" : "refresh-cw");
    // What the button always sends besides its fields (Button#constant), which win over it.
    const constants = bodyFor(button.body, {});
    const node = h("button", { class: "btn btn-secondary glass glass-set", type: "button", data: { tone: STYLE_TONE[button.style] || null } }, icon(glyph), label);

    /** Asked as the reader is — a link could not carry the session — then handed over to save. */
    const fetchFile = async (values = {}) => {
      try {
        const file = await download(withQuery(button.endpoint, { ...query(), ...constants, ...values }));
        saveFile(file.blob, file.name);
      } catch (error) {
        throw new Error((error && error.message) || t("download-failed"));
      }
    };

    node.addEventListener("click", async () => {
      // A download with nothing to ask starts at once, the button waiting on it.
      if (reads && !asks && !button.confirmKey) {
        node.disabled = true;
        swap(node, h("span", { class: "spin", "aria-hidden": "true" }), label);
        try {
          await fetchFile();
        } catch (error) {
          toast(error.message, "error");
        } finally {
          node.disabled = false;
          swap(node, icon(glyph), label);
        }
        return;
      }
      const sent = await openDialog({
        title: label,
        text: button.confirmKey ? t(button.confirmKey) : "",
        fields: fieldsFor(button),
        confirmLabel: label,
        tone: button.style === "danger" ? "danger" : "",
        sendEmpty: true,
        onConfirm: (values) => (reads ? fetchFile(values) : send(button.endpoint, { ...context(), ...constants, ...values })),
      });
      if (sent === null || reads) return;
      toast(t("action-completed-successfully"), "success");
      done();
    });
    return node;
  });
}

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
  if (spec.type === "grid") return gridPage(mod, page);
  return tablePage(mod, page);
}

/**
 * Whether the reader may only read a configuration page: the panel marks it so when saving needs
 * what they lack — the permissions it names, or being an operator, for a plugin that names none.
 */
function readOnlyOf(page) {
  if (!page || page.readOnly !== true) return null;
  const all = requirements(page.needsAll);
  return { needs: all.length ? all : requirements(page.needs), operator: page.operator === true };
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

/**
 * A grid page: one container at a time, as the game shows an inventory. What draws it is loaded
 * when one is opened, like the configuration editor: most servers have no such page.
 */
function gridPage(mod, page) {
  const buttons = h("div", { class: "page-actions", hidden: true });
  const holder = h("div", { class: "grid-holder" }, h("section", { class: "card card-flush", "aria-busy": "true" }, skeletonRows(5)));
  const head = pageHead({ eyebrow: moduleEyebrow(mod), title: pageTitle(mod, page) });
  head.append(buttons);
  let view = null;
  return {
    el: h("div", { class: "page", vars: { mark: markOf(mod).mark } }, head, holder),
    async mounted() {
      const { gridView } = await import("./grid.js");
      if (!holder.isConnected) return;
      view = gridView(mod, page, buttons);
      swap(holder, view.el);
      view.load();
    },
    destroy() {
      if (view) view.destroy();
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

  // What the reader narrowed the page to — a shop, a period. The page's endpoint is read again
  // with it in its query string, and so is every chart and list that has an endpoint of its own.
  const chosen = {};
  const lenses = [];
  const selects = filtersOf(spec).map((filter) =>
    filterSelect(filter, (param, value) => {
      chosen[param] = value;
      load(true);
    })
  );
  let toolbar = null;
  if (selects.length) {
    toolbar = h("div", { class: "toolbar toolbar-filters glass", role: "group", "aria-label": t("filter") }, selects.map((select) => select.el));
    lenses.push(attachLens(toolbar));
  }

  const el = h(
    "div",
    { class: "page", vars: { mark: markOf(mod).mark } },
    pageHead({
      eyebrow: moduleEyebrow(mod),
      title: pageTitle(mod, page),
      actions: [...pageButtons(spec, { query: () => ({ ...chosen }), done: () => load(true) }), refresh],
    }),
    toolbar,
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
  // Short tables under the charts: the best sellers, the last few events.
  const lists = (spec.lists || []).map((list) => ({
    list,
    slot: h("div", { class: "list-slot" }, skeletonRows(3)),
  }));
  const listCards = lists.length
    ? h(
        "div",
        { class: "lists" },
        lists.map(({ list, slot }) => h("section", { class: "card card-flush" }, h("div", { class: "card-head" }, h("h2", { class: "card-title", text: t(list.titleKey) })), slot))
      )
    : null;

  const grid = h("div", { class: "grid-2" }, togglesCard, detailsCard);
  below.append(...charts.map((entry) => entry.card), heatCard || "", listCards || "", grid);

  const fillBody = (card, ...nodes) => {
    const head = card.querySelector(".card-head");
    swap(card, head, ...nodes);
  };

  // A payload that lists worlds and names the one it describes can be asked for another
  // (?world=): the heat map offers the switch, and the whole page follows it.
  let world = "";
  let ticket = 0;

  async function load(again = false) {
    const mine = ++ticket;
    const query = { ...chosen };
    // What a chart, a list or the heat map is drawn from: the page's answer, or its own endpoint's,
    // asked once however many of them share it — two series of the same day-by-day figures.
    const asked = new Map();
    const sourceOf = (part, data) => {
      if (!part.endpoint || part.endpoint === spec.endpoint) return data;
      const url = withQuery(part.endpoint, query);
      if (!asked.has(url)) asked.set(url, get(url).catch(() => null));
      return asked.get(url);
    };
    selects.forEach((select) => select.fill());
    if (again) {
      refresh.disabled = true;
      tiles.setAttribute("data-busy", "");
      below.setAttribute("data-busy", "");
    }
    try {
      const data = (await get(withQuery(spec.endpoint, { world: world || null, ...query }))) || {};
      // A choice made since: the answer to it is on its way, and this one is already old.
      if (mine !== ticket) return;
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

      // Each list on its own time: a slow one holds nothing else back.
      for (const { list, slot } of lists) {
        Promise.resolve(sourceOf(list, data)).then((source) => {
          if (mine !== ticket) return;
          const { rows } = rowsOf(source, { dataKeys: list.dataKey ? [list.dataKey] : null });
          swap(slot, source && rows.length ? dataTable(rows, { caption: t(list.titleKey) }) : emptyState(t(list.emptyKey || "no-data-found"), "list"));
        });
      }

      if (charts.length || heatCard) {
        const { barChart, heatmap } = await import("./charts.js");
        for (const { chart, card } of charts) {
          const source = await sourceOf(chart, data);
          if (mine !== ticket) return;
          const { rows } = rowsOf(source, { dataKeys: chart.dataKey ? [chart.dataKey] : null });
          const slot = card.querySelector(".chart-slot");
          if (!source || rows.length === 0) swap(slot, emptyState(t(chart.emptyKey || "no-chart-data-available"), "chart-column"));
          else swap(slot, barChart(rows, chart));
        }
        if (heatCard) {
          const conf = spec.heatmap;
          const source = await sourceOf(conf, data);
          if (mine !== ticket) return;
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
      if (mine !== ticket) return;
      swap(tiles, h("div", { class: "card tiles-error" }, errorState(t(spec.errorKey || "failed-to-load-data"), () => load(true))));
      tiles.removeAttribute("aria-busy");
    } finally {
      if (mine === ticket) {
        refresh.disabled = false;
        tiles.removeAttribute("data-busy");
        below.removeAttribute("data-busy");
      }
    }
  }

  return { el, mounted: () => load(), destroy: () => lenses.forEach((undo) => undo()) };
}

function tablePage(mod, page) {
  const spec = page.spec;
  const title = pageTitle(mod, page);
  const mark = markOf(mod).mark;
  const actions = [];

  // "Choose where" only means something on a grid: a table leaves such a button out.
  const rowActions = (spec.actions || [])
    .filter((action) => action.kind !== "pick")
    .map((action) => ({
      label: t(action.labelKey),
      tone: STYLE_TONE[action.style] || null,
      when: action.when ? (row) => applies(action, row) : null,
      run: async (row) => {
        if ((await runAction(action, row, { mark })) && action.reload !== false) list.reload();
      },
    }));

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

  actions.push(...pageButtons(spec, { query: () => list.query(), done: () => list.reload() }));

  if (spec.form) {
    const label = t(spec.form.titleKey);
    actions.push(
      h(
        "button",
        {
          class: "btn glass-brass",
          type: "button",
          onclick: async () => {
            // A form that creates leaves out what the reader left empty, as it always did.
            const values = await openDialog({
              title: label,
              fields: fieldsOf(spec.form.fields),
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
    filters: filtersOf(spec),
    pageSize: spec.pageSize || 0,
    actions: rowActions,
    columns: namedColumns(spec),
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
 * A sentence with permission nodes in it, each set as code — as the login screen sets the
 * command — joined the way they are needed: "a and b", each "x or y". The translation places them
 * with {permission}; a sentence for several takes the key's "-all" form.
 *
 * @param {string} key The translation.
 * @param {Array} needs The requirements (see requirements()), or one list of alternatives.
 */
function withNode(key, needs) {
  const list = requirements(needs);
  const text = t(list.length > 1 ? `${key}-all` : key);
  const nodes = [];
  list.forEach((anyOf, i) => {
    if (i > 0) nodes.push(t("permission-and"));
    anyOf.forEach((node, j) => {
      if (j > 0) nodes.push(t("permission-or"));
      nodes.push(h("code", { text: node }));
    });
  });
  const at = text.indexOf("{permission}");
  if (at < 0) return h("p", null, text, " ", ...nodes);
  return h("p", null, text.slice(0, at), ...nodes, text.slice(at + "{permission}".length));
}

/**
 * A page this session may not open — a module, the players, the configuration editor, a page
 * showing addresses. Said calmly, with the permission that would open it, so the reader can ask
 * for it by name.
 *
 * @param {object} options
 * @param {object|null} [options.mod]  The module, when the page is one of a module's.
 * @param {Array} [options.needs]      What opens it: the requirements, or one list of alternatives.
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
        requirements(needs).length ? withNode("no-access-needs", needs) : null,
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

export { moduleEyebrow, bodyFor, send, readOnlyOf, withNode, applies, fieldsOf, runAction, pageButtons, STYLE_TONE };

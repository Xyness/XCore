// Pages that do not describe themselves, loaded only when one is opened.
//
// Every Celestis plugin declares its pages with a WebPageSpec. A module written before that
// existed still gets what the dashboard always gave it: the configuration editor for a "config"
// page, a player filter on "transactions", and otherwise its answer shown as a table or a list.
// Two older modules had pages of their own in the dashboard, XShops and XAutoMessage; they keep
// them, against the same routes as before. Their pages declare no permission for what they
// change, so the panel asks for the module's manage permission: without it, the buttons that
// would only be refused are not drawn.

import { h, icon, swap } from "../lib/dom.js";
import { get, post, withQuery, moduleBase } from "../lib/api.js";
import { t, fieldLabel, capitalize } from "../lib/i18n.js";
import { listing, rowsOf } from "../ui/listing.js";
import { dataTable, emptyState, errorState, cell, skeletonRows } from "../ui/table.js";
import { openDialog, toast } from "../ui/overlay.js";
import { pageHead } from "./players.js";
import { moduleEyebrow, send, readOnlyOf } from "./module.js";
import { pageTitle } from "../lib/modules.js";
import { holds, managePermission } from "../lib/access.js";

function shell(mod, page, actions, ...body) {
  return h("div", { class: "page" }, pageHead({ eyebrow: moduleEyebrow(mod), title: pageTitle(mod, page), actions }), ...body);
}

/** Whatever the route answers: a table for a list, a list of fields for an object. */
function showAnything(target, data) {
  const { rows } = rowsOf(data, {});
  if (Array.isArray(data) || rows.length) {
    swap(target, rows.length ? dataTable(rows) : emptyState(t("no-data-found")));
    return;
  }
  if (!data || typeof data !== "object") {
    swap(target, emptyState(t("no-data-found")));
    return;
  }
  swap(
    target,
    h(
      "div",
      { class: "kv card-pad" },
      Object.keys(data)
        .filter((key) => typeof data[key] !== "object" || data[key] === null)
        .map((key) => h("div", { class: "kv-row" }, h("span", { class: "kv-key", text: fieldLabel(key) }), h("span", { class: "kv-value" }, cell(key, data[key], false))))
    )
  );
}

function genericPage(mod, page) {
  const base = moduleBase(mod.name);
  const box = h("section", { class: "card card-flush" }, skeletonRows());

  if (page.path === "transactions") {
    const list = listing({
      search: t("player-name-optional"),
      empty: t("no-data-found"),
      error: t("failed-to-load-data"),
      async load({ search }) {
        const data = await get(withQuery(`${base}/transactions`, { offset: 0, limit: 100, player: search }));
        return rowsOf(data, {});
      },
    });
    return { el: shell(mod, page, null, list.el), mounted: () => list.reload(), destroy: list.destroy };
  }

  const load = () =>
    get(`${base}/${page.path}`)
      .then((data) => showAnything(box, data))
      .catch(() => swap(box, errorState(t("failed-to-load-data"), load)));
  return { el: shell(mod, page, null, box), mounted: load };
}

// ---------------------------------------------------------------------------
// XShops
// ---------------------------------------------------------------------------

function shopsPage(mod, page) {
  const path = page.path;
  const manage = holds(managePermission(mod.name));
  const simple = (endpoint, keys, empty, error, actions = null) => {
    const list = listing({
      empty: t(empty),
      error: t(error),
      actions,
      async load() {
        const data = await get(endpoint);
        return rowsOf(data, { dataKeys: keys });
      },
    });
    return list;
  };

  if (path === "shops") {
    const list = simple("/api/xshops/shops", ["shops", "data"], "no-shops-found", "failed-to-load-shops");
    return { el: shell(mod, page, null, list.el), mounted: () => list.reload(), destroy: list.destroy };
  }

  if (path === "stock") {
    const list = simple("/api/xshops/stock", ["stock", "data"], "no-stock-data", "failed-to-load-stock", !manage ? null : [
      {
        label: t("edit-stock"),
        run: async (row) => {
          const current = row.stock ?? row.quantity ?? row.amount ?? "";
          const values = await openDialog({
            title: t("edit-stock"),
            fields: [{ key: "stock", label: t("stock"), type: "number", placeholder: String(current), required: true }],
            confirmLabel: t("save"),
            onConfirm: (body) =>
              send("/api/xshops/stock/update", {
                id: row.id,
                shop: row.shop || row.shop_name,
                item: row.item || row.item_id || row.material,
                stock: parseInt(body.stock, 10),
              }),
          });
          if (values === null) return;
          toast(t("stock-updated"), "success");
          list.reload();
        },
      },
    ]);
    const restock = h(
      "button",
      {
        class: "btn btn-secondary glass glass-set",
        type: "button",
        data: { tone: "success" },
        onclick: async () => {
          const done = await openDialog({
            title: t("restock-all"),
            text: t("restock-all-shops-to-their-maximum"),
            confirmLabel: t("restock-all"),
            onConfirm: () => send("/api/xshops/stock/restock", {}),
          });
          if (done === null) return;
          toast(t("all-shops-restocked"), "success");
          list.reload();
        },
      },
      icon("refresh-cw"),
      t("restock-all")
    );
    return { el: shell(mod, page, manage ? [restock] : null, list.el), mounted: () => list.reload(), destroy: list.destroy };
  }

  if (path === "prices") {
    const list = simple("/api/xshops/prices", ["prices", "data"], "no-price-data", "failed-to-load-prices");
    const reset = h(
      "button",
      {
        class: "btn btn-secondary glass glass-set",
        type: "button",
        data: { tone: "warning" },
        onclick: async () => {
          const done = await openDialog({
            title: t("reset-all-prices"),
            text: t("reset-all-prices-to-default-this-cannot-be-undon"),
            confirmLabel: t("reset-all-prices"),
            tone: "danger",
            onConfirm: () => send("/api/xshops/prices/reset", {}),
          });
          if (done === null) return;
          toast(t("all-prices-reset"), "success");
          list.reload();
        },
      },
      icon("rotate-ccw"),
      t("reset-all-prices")
    );
    return { el: shell(mod, page, manage ? [reset] : null, list.el), mounted: () => list.reload(), destroy: list.destroy };
  }

  if (path === "transactions") {
    const list = listing({
      search: t("search-by-player"),
      filters: [{ param: "action", placeholder: t("all-actions"), options: [{ value: "BUY", label: t("buy") }, { value: "SELL", label: t("sell") }] }],
      pageSize: 50,
      empty: t("no-transactions-found"),
      error: t("failed-to-load-transactions"),
      async load({ page: number, search, filters }) {
        const data = await get(withQuery("/api/xshops/transactions/search", { page: number, limit: 50, player: search, action: filters.action }));
        return rowsOf(data, { dataKeys: ["transactions", "data"], totalKeys: ["total", "total_count"], pageSize: 50, page: number });
      },
    });
    return { el: shell(mod, page, null, list.el), mounted: () => list.reload(), destroy: list.destroy };
  }

  if (path === "stats") {
    const tiles = h("div", { class: "tiles" }, skeletonRows(1));
    const chartCard = h("section", { class: "card chart-card" }, h("div", { class: "card-head" }, h("h2", { class: "card-title", text: t("daily-sales-volume") })), h("div", { class: "chart-slot" }, h("span", { class: "skel skel-chart" })));
    const tile = (label, value, tone) =>
      h("div", { class: "tile", data: { tone } }, h("p", { class: "tile-label label" }, h("span", { class: "tile-dot" }), h("span", { text: label })), h("p", { class: "tile-value" }, cell("value", value)));
    return {
      el: shell(mod, page, null, tiles, h("div", { class: "section" }, chartCard)),
      async mounted() {
        get("/api/xshops/stats")
          .then((s) =>
            swap(
              tiles,
              tile(fieldLabel("total_shops"), s.total_shops ?? "—"),
              tile(fieldLabel("total_items"), s.total_items ?? "—", "cyan"),
              tile(fieldLabel("transactions_today"), s.transactions_today ?? "—", "green"),
              tile(fieldLabel("revenue_today"), s.revenue_today ?? "—", "purple"),
              s.total_transactions != null ? tile(fieldLabel("total_transactions"), s.total_transactions, "orange") : null
            )
          )
          .catch(() => swap(tiles, h("div", { class: "card tiles-error" }, errorState(t("failed-to-load-stats")))));
        try {
          const data = await get("/api/xshops/stats/daily");
          const { rows } = rowsOf(data, { dataKeys: ["days", "data"] });
          const slot = chartCard.querySelector(".chart-slot");
          if (!rows.length) return swap(slot, emptyState(t("no-daily-data-available"), "chart-column"));
          const { barChart } = await import("./charts.js");
          swap(slot, barChart(rows, { titleKey: "daily-sales-volume", labelField: "date", valueField: "volume" }));
        } catch {
          swap(chartCard.querySelector(".chart-slot"), emptyState(t("no-daily-data-available"), "chart-column"));
        }
      },
    };
  }

  return genericPage(mod, page);
}

// ---------------------------------------------------------------------------
// XAutoMessage
// ---------------------------------------------------------------------------

function autoMessagePage(mod, page) {
  const manage = holds(managePermission(mod.name));
  if (page.path === "status") {
    const box = h("section", { class: "card" }, h("span", { class: "skel" }));
    const load = () =>
      get("/api/automessage/status")
        .then((data) =>
          swap(
            box,
            h(
              "div",
              { class: "kv" },
              Object.keys(data || {})
                .filter((key) => typeof data[key] !== "object")
                .map((key) =>
                  h(
                    "div",
                    { class: "kv-row" },
                    h("span", { class: "kv-key", text: fieldLabel(key) }),
                    typeof data[key] === "boolean"
                      ? h("span", { class: "badge", data: { tone: data[key] ? "success" : "neutral" } }, t(data[key] ? "status-enabled" : "status-disabled"))
                      : h("span", { class: "kv-value" }, cell(key, data[key], false))
                  )
                )
            )
          )
        )
        .catch(() => swap(box, errorState(t("failed-to-load-status"), load)));
    return { el: shell(mod, page, null, box), mounted: load };
  }

  if (page.path === "messages") {
    const box = h("div", { class: "stack" }, h("section", { class: "card card-flush" }, skeletonRows(4)));
    const TONES = { BOSSBAR: "purple", TITLE: "orange", ACTIONBAR: "cyan", CHAT: "green" };

    const load = () =>
      get("/api/automessage/messages")
        .then((data) => {
          const sections = {};
          if (Array.isArray(data)) {
            for (const message of data) (sections[message.type || "other"] ||= []).push(message);
          } else if (data && typeof data === "object") {
            for (const key in data) if (Array.isArray(data[key])) sections[key] = data[key];
            if (Object.keys(sections).length === 0) sections.messages = [data];
          }
          const cards = Object.keys(sections)
            .filter((type) => sections[type].length)
            .map((type) =>
              h(
                "section",
                { class: "card" },
                h("div", { class: "card-head" }, h("h2", { class: "card-title", text: capitalize(type) })),
                h(
                  "ul",
                  { class: "messages" },
                  sections[type].map((message) => {
                    const enabled = message.enabled !== false && message.enabled !== "false" && message.enabled !== 0;
                    const id = `msg-${String(message.id ?? Math.random()).replace(/[^a-z0-9_-]/gi, "")}`;
                    const toggle = h("input", { class: "switch", type: "checkbox", role: "switch", id, checked: enabled, disabled: !manage });
                    toggle.addEventListener("change", () => {
                      post("/api/automessage/toggle", { id: message.id, enabled: toggle.checked })
                        .then(() => toast(t(toggle.checked ? "status-enabled" : "status-disabled"), "success"))
                        .catch((error) => {
                          toggle.checked = !toggle.checked;
                          toast(error.message || t("action-failed"), "error");
                        });
                    });
                    const kind = message.type ? String(message.type).toUpperCase() : null;
                    return h(
                      "li",
                      { class: "message" },
                      h(
                        "div",
                        { class: "message-body" },
                        kind ? h("span", { class: "badge", data: { tone: TONES[kind] || "info" }, text: kind }) : null,
                        h("p", { class: "message-text", text: message.message || message.text || message.content || JSON.stringify(message) })
                      ),
                      h("label", { class: "sr-only", for: id, text: t("status-enabled") }),
                      toggle
                    );
                  })
                )
              )
            );
          swap(box, cards.length ? cards : h("section", { class: "card" }, emptyState(t("no-messages-configured"), "mail")));
        })
        .catch(() => swap(box, h("section", { class: "card" }, errorState(t("failed-to-load-messages"), load))));

    const reload = h(
      "button",
      {
        class: "btn btn-secondary glass glass-set",
        type: "button",
        onclick: async () => {
          const done = await openDialog({
            title: t("reload-messages"),
            text: t("reload-all-messages-from-config"),
            confirmLabel: t("reload-messages"),
            onConfirm: () => send("/api/automessage/reload", {}),
          });
          if (done === null) return;
          toast(t("messages-reloaded"), "success");
          load();
        },
      },
      icon("refresh-cw"),
      t("reload-messages")
    );
    return { el: shell(mod, page, manage ? [reload] : null, box), mounted: load };
  }

  return genericPage(mod, page);
}

export function legacyPage(mod, page) {
  if (page.path === "config") {
    let editor = null;
    const holder = h("div", { class: "config-holder" });
    return {
      el: shell(mod, page, null, holder),
      async mounted() {
        const { configEditor } = await import("./config.js");
        editor = configEditor(mod.name, readOnlyOf(page));
        swap(holder, editor.el);
      },
      destroy: () => editor && editor.destroy(),
    };
  }
  if (mod.name === "XShops") return shopsPage(mod, page);
  if (mod.name === "XAutoMessage") return autoMessagePage(mod, page);
  return genericPage(mod, page);
}

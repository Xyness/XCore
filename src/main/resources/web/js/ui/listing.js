// A list a reader can search, filter and page through: the toolbar, the table, the pager.
//
// The players page and every table a module declares are this one piece. It keeps its state —
// page, search, filters — and asks its owner for rows whenever that state changes. The search
// answers as you type, a moment after you stop, and at once on Enter; the table already shown
// stays in place, dimmed, while the next page is on its way, so nothing jumps.

import { h, icon, swap, debounce } from "../lib/dom.js";
import { attachLens } from "../lib/glass.js";
import { t, num } from "../lib/i18n.js";
import { dataTable, skeletonRows, emptyState, errorState, pager } from "./table.js";

/**
 * @param {object} options
 * @param {(state: {page: number, search: string, filters: object}) => Promise<{rows: object[], total: number}>} options.load
 * @param {Array} [options.actions]   Row actions for dataTable.
 * @param {Array} [options.columns]   Explicit columns for dataTable.
 * @param {string} [options.search]   Placeholder of the search box; no box without it.
 * @param {Array} [options.filters]   [{ param, placeholder, options: [{ value, label }] }]
 * @param {number} [options.pageSize] Rows per page; no pager without it.
 * @param {string} options.empty      What to say when there is nothing.
 * @param {string} options.error      What to say when loading failed.
 * @param {string} [options.caption]  Accessible name of the table.
 * @param {string} [options.emptyIcon]
 */
export function listing(options) {
  const state = { page: 1, search: "", filters: {} };
  const lenses = [];
  let ticket = 0;
  let shown = false;

  const results = h("section", { class: "card card-flush listing", "aria-live": "polite", "aria-busy": "true" }, skeletonRows());
  const pagerBox = h("div", { class: "pager-box" });
  const count = h("span", { class: "toolbar-count label num" });

  let toolbar = null;
  let input = null;
  if (options.search || (options.filters && options.filters.length)) {
    const parts = [];
    if (options.search) {
      input = h("input", {
        class: "input",
        type: "search",
        placeholder: options.search,
        "aria-label": options.search,
        autocomplete: "off",
        spellcheck: false,
        enterkeyhint: "search",
      });
      const clear = h(
        "button",
        { class: "search-clear", type: "button", "aria-label": t("clear-search"), hidden: true },
        icon("x")
      );
      const typed = debounce(() => apply(), 350);
      input.addEventListener("input", () => {
        clear.hidden = input.value === "";
        typed();
      });
      input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          typed.cancel();
          apply();
        } else if (event.key === "Escape" && input.value) {
          input.value = "";
          clear.hidden = true;
          typed.cancel();
          apply();
        }
      });
      clear.addEventListener("click", () => {
        input.value = "";
        clear.hidden = true;
        apply();
        input.focus();
      });
      parts.push(h("label", { class: "search" }, icon("search"), input, clear));
    }
    for (const filter of options.filters || []) {
      const select = h(
        "select",
        { class: "select", "aria-label": filter.placeholder, data: { param: filter.param } },
        h("option", { value: "", text: filter.placeholder }),
        filter.options.map((option) => h("option", { value: option.value, text: option.label }))
      );
      select.addEventListener("change", () => {
        state.filters[filter.param] = select.value;
        state.page = 1;
        load();
      });
      parts.push(select);
    }
    parts.push(count);
    toolbar = h("div", { class: "toolbar glass", role: "search" }, parts);
    lenses.push(attachLens(toolbar));
  }

  function apply() {
    const value = input ? input.value.trim() : "";
    if (value === state.search) return;
    state.search = value;
    state.page = 1;
    load();
  }

  async function load(retried = false) {
    const mine = ++ticket;
    results.setAttribute("aria-busy", "true");
    if (shown) results.setAttribute("data-busy", "");
    let answer;
    try {
      answer = await options.load({ ...state, filters: { ...state.filters } });
    } catch (error) {
      if (mine !== ticket) return;
      results.removeAttribute("data-busy");
      results.setAttribute("aria-busy", "false");
      shown = false;
      swap(results, errorState(options.error, () => load()));
      swap(pagerBox);
      count.textContent = "";
      return;
    }
    if (mine !== ticket) return;
    const { rows, total } = answer;
    results.removeAttribute("data-busy");
    results.setAttribute("aria-busy", "false");

    // Asked for a page past the end (rows removed meanwhile): go back to the last one there is.
    if (rows.length === 0 && state.page > 1 && options.pageSize && !retried) {
      state.page = Math.max(1, Math.ceil((total || 0) / options.pageSize));
      return load(true);
    }

    count.textContent = typeof total === "number" ? t("results-count", { count: num(total) }) : "";

    if (rows.length === 0) {
      shown = false;
      swap(results, emptyState(state.search || Object.values(state.filters).some(Boolean) ? t("no-match") : options.empty, options.emptyIcon || "list"));
      swap(pagerBox);
      return;
    }

    shown = true;
    swap(results, dataTable(rows, { actions: options.actions || [], columns: options.columns, caption: options.caption }));

    const pages = options.pageSize ? Math.max(1, Math.ceil((total || rows.length) / options.pageSize)) : 1;
    if (pages > 1) {
      swap(
        pagerBox,
        pager(state.page, pages, (page) => {
          state.page = page;
          load().then(() => {
            // Back to the top of the list, not of the page: the header has not changed.
            const anchor = toolbar || results;
            const y = anchor.getBoundingClientRect().top + window.scrollY - 96;
            if (window.scrollY > y) window.scrollTo({ top: Math.max(0, y), behavior: "instant" });
          });
        })
      );
    } else {
      swap(pagerBox);
    }
  }

  return {
    el: h("div", { class: "listing-wrap" }, toolbar, results, pagerBox),
    reload: () => load(),
    focusSearch: () => input && input.focus(),
    destroy: () => lenses.forEach((undo) => undo()),
  };
}

/**
 * Turns a module's answer into rows and a total, the way the old dashboard read them: a bare
 * array, or the first array among the fields named by the page, or any array at all.
 *
 * When the endpoint pages itself (it answers with a total or a page number) its rows are the
 * page; when it answers with everything, as most do, the page is cut here rather than not at all.
 */
export function rowsOf(data, { dataKeys, totalKeys, pageSize, page }) {
  let rows = [];
  if (Array.isArray(data)) rows = data;
  else if (data && typeof data === "object") {
    const named = pick(data, dataKeys);
    if (Array.isArray(named)) rows = named;
    else for (const key in data) if (Array.isArray(data[key])) { rows = data[key]; break; }
  }
  const serverPaged = !!data && !Array.isArray(data) && (data.total !== undefined || data.page !== undefined);
  if (pageSize && !serverPaged) {
    const total = rows.length;
    const from = (page - 1) * pageSize;
    return { rows: rows.slice(from, from + pageSize), total };
  }
  const declared = serverPaged ? pick(data, totalKeys && totalKeys.length ? totalKeys : ["total", "total_count"]) : null;
  return { rows, total: typeof declared === "number" ? declared : serverPaged ? rows.length : rows.length };
}

/** The first field of `obj` present among `keys`. */
export function pick(obj, keys) {
  if (!obj || !keys) return null;
  for (const key of keys) {
    const value = obj[key];
    if (value !== undefined && value !== null) return value;
  }
  return null;
}

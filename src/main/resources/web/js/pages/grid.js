// A grid page: one container at a time, seen from above as the game shows an inventory.
//
// A module declares it with WebPageSpec.grid: its endpoint lists the containers there are — the
// shops, the kits, the crates — and answers one page of one of them as rows of nine cells. Each
// cell is a row like a table's: its item draws the icon, `label` and `sub` the two short lines
// under it, and everything else is listed in the pane a click opens, with the page's actions. An
// empty cell opens the page's creation form, sent with where it was opened: the container, the
// page and the slot.
//
// An action may ask for a destination ("move"): the grid then waits for a second cell, on this
// page or another, and sends it as to_page and to_slot. After anything that changed something
// the grid is read again where it is — same container, same page.
//
// The cells are one stop for the Tab key, and the arrows walk them; so are the containers.
// Loaded only when a grid page is opened.

import { h, icon, swap } from "../lib/dom.js";
import { get, withQuery } from "../lib/api.js";
import { t, num, fieldLabel, itemName } from "../lib/i18n.js";
import { markOf, iconFor, pageTitle } from "../lib/modules.js";
import { sprite, cell, pager, emptyState, errorState, attachTip, tooltipFor, materialDetails, onItemNames } from "../ui/table.js";
import { openDialog, openPane, toast } from "../ui/overlay.js";
import { bodyFor, send, applies, fieldsOf, runAction, pageButtons, STYLE_TONE } from "./module.js";

const COLUMNS = 9;

/** What a cell's row says about how it is drawn, which the pane does not repeat as a field. */
const DRAWN = new Set(["slot", "material", "itemDetails", "label", "sub", "tone", "amount"]);

const TONES = new Set(["success", "warning", "danger"]);

/** Where each grid was left — which container, which page — so coming back does not start over. */
const left = new Map();

/** The item of a row, with whatever the game's tooltip can say about it. */
const detailsOf = (row) => (row.itemDetails && row.itemDetails.material ? row.itemDetails : materialDetails(row.material || "", row.amount));

/** How many of the item a cell stands for: its own count, or the item's. */
const amountOf = (row) => Number(row.amount ?? (row.itemDetails && row.itemDetails.amount)) || 0;

const said = (value) => (value === null || value === undefined ? "" : String(value));

/** Scrolls a box, and nothing around it, until one of its children is in view. */
function reveal(box, node) {
  if (!node) return;
  const outer = box.getBoundingClientRect();
  const inner = node.getBoundingClientRect();
  if (inner.left < outer.left) box.scrollLeft -= outer.left - inner.left + 8;
  else if (inner.right > outer.right) box.scrollLeft += inner.right - outer.right + 8;
  if (inner.top < outer.top) box.scrollTop -= outer.top - inner.top + 8;
  else if (inner.bottom > outer.bottom) box.scrollTop += inner.bottom - outer.bottom + 8;
}

/**
 * Says on the list of containers which of its sides it goes on past — sideways while it is a
 * strip, downwards once it is a column — so the stylesheet fades them out, as a wide table's.
 * Returns what to call when its content changed: that resizes nothing it could observe.
 */
function watchRail(rail) {
  let frame = 0;
  const update = () => {
    frame = 0;
    const more = [];
    if (rail.scrollLeft > 2) more.push("start");
    if (rail.scrollLeft + rail.clientWidth < rail.scrollWidth - 2) more.push("end");
    if (rail.scrollTop > 2) more.push("top");
    if (rail.scrollTop + rail.clientHeight < rail.scrollHeight - 2) more.push("bottom");
    if (more.length) rail.dataset.more = more.join(" ");
    else delete rail.dataset.more;
  };
  const schedule = () => {
    if (!frame) frame = requestAnimationFrame(update);
  };
  rail.addEventListener("scroll", schedule, { passive: true });
  new ResizeObserver(schedule).observe(rail);
  return schedule;
}

/** The icon of a button drawn with one: the action's own, or one that fits what it does. */
const glyphOf = (action) =>
  iconFor(action.icon, action.kind === "chart" ? "chart-column" : action.kind === "pick" ? "move" : action.style === "danger" ? "trash-2" : action.fields ? "pencil" : "check");

/**
 * @param {object} mod      The module.
 * @param {object} page     The page, with its spec.
 * @param {HTMLElement} bar Where the page's own buttons go, in its head.
 */
export function gridView(mod, page, bar) {
  const spec = page.spec;
  const param = (spec.grid && spec.grid.containerParam) || "container";
  const mark = markOf(mod).mark;
  const title = pageTitle(mod, page);
  const memory = `${mod.name}/${page.path}`;
  const state = { container: "", page: 1, ...left.get(memory) };

  /** The last answer, read: { containers, shown, rows, page, pages, slots, locked }. */
  let data = null;
  /** A move under way: the action, what it moves, and the request it will send once told where. */
  let picking = null;
  /** The cell the arrow keys are on. */
  let cursor = -1;
  let ticket = 0;
  let sending = false;
  let railDrawn = "";
  let hintFor = null;
  /** The cells already built, by slot: one that did not change keeps its node, and its icon. */
  const built = new Map();

  const titleId = `grid-${Math.random().toString(36).slice(2, 8)}`;
  const panelId = `${titleId}-panel`;
  const rail = h("div", { class: "grid-rail", role: "tablist", "aria-label": title, hidden: true });
  const head = h("div", { class: "card-head grid-head" });
  const hint = h("div", { class: "grid-hint", role: "status", hidden: true });
  const inv = h("div", { class: "inv", role: "group", "aria-labelledby": titleId });
  const stage = h("div", { class: "inv-stage" }, inv);
  const pagerBox = h("div", { class: "pager-box" });
  // The list of containers is a row of tabs, and the card the panel they switch.
  const card = h("section", { class: "card grid-card", id: panelId, role: "tabpanel", "aria-labelledby": titleId, "aria-busy": "true" }, head, hint, stage, pagerBox);
  const el = h("div", { class: "grid-view" }, h("div", { class: "grid-layout" }, rail, card));
  const railMoved = watchRail(rail);

  // Cells that stand in for the real ones until the first answer: the shape is known already.
  swap(inv, Array.from({ length: 6 * COLUMNS }, () => h("span", { class: "slot", data: { ghost: true }, "aria-hidden": "true" })));

  /** What goes with every request about the container on screen: the cell actions, the buttons. */
  const here = () => (data && data.shown ? { [param]: data.shown.id } : {});
  /** The same, down to the page: where the creation form puts what it creates, with the slot. */
  const where = () => ({ ...here(), page: data ? data.page : state.page });

  const pageBar = pageButtons(spec, { context: here, done: () => load() });
  if (pageBar.length) {
    swap(bar, pageBar);
    bar.hidden = false;
  }

  // ---------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------

  /** Reads the endpoint's answer into what the grid draws, forgiving what is missing from it. */
  function read(answer) {
    const from = answer && typeof answer === "object" ? answer : {};
    const containers = (Array.isArray(from.containers) ? from.containers : []).filter((entry) => entry && entry.id !== undefined && entry.id !== null);
    const named = (id) => containers.find((entry) => String(entry.id) === String(id));
    const shown = named(from.container) || named(state.container) || containers[0] || null;
    const rows = Math.min(12, Math.max(1, Math.trunc(Number(from.rows)) || 6));
    const pages = Math.max(1, Math.trunc(Number(from.pages)) || 1);
    const slots = new Map();
    for (const row of Array.isArray(from.slots) ? from.slots : []) {
      if (row && Number.isInteger(row.slot) && row.slot >= 0 && row.slot < rows * COLUMNS) slots.set(row.slot, row);
    }
    return {
      containers,
      shown,
      rows,
      pages,
      page: Math.min(pages, Math.max(1, Math.trunc(Number(from.page)) || state.page)),
      slots,
      locked: new Set((Array.isArray(from.locked) ? from.locked : []).filter(Number.isInteger)),
    };
  }

  async function load() {
    const mine = ++ticket;
    card.setAttribute("aria-busy", "true");
    if (data) card.setAttribute("data-busy", "");
    let answer;
    try {
      answer = await get(withQuery(spec.endpoint, { [param]: state.container, page: state.page }));
    } catch {
      if (mine !== ticket) return;
      data = null;
      picking = null;
      rail.hidden = true;
      card.removeAttribute("data-busy");
      card.setAttribute("aria-busy", "false");
      swap(card, errorState(t(spec.errorKey || "failed-to-load-data"), load));
      return;
    }
    if (mine !== ticket) return;
    data = read(answer);
    // The server says what it shows: the container asked for, or the first when that one is gone.
    state.container = data.shown ? String(data.shown.id) : "";
    state.page = data.page;
    left.set(memory, { ...state });
    card.removeAttribute("data-busy");
    card.setAttribute("aria-busy", "false");
    draw();
  }

  // ---------------------------------------------------------------------------
  // Drawing
  // ---------------------------------------------------------------------------

  const labelOf = (entry) => said(entry.label) || said(entry.id);

  function drawRail() {
    rail.hidden = data.containers.length < 2;
    const signature = JSON.stringify(data.containers.map((entry) => [entry.id, entry.label, entry.material, entry.count]));
    if (signature !== railDrawn) {
      railDrawn = signature;
      swap(
        rail,
        data.containers.map((entry) =>
          h(
            "button",
            { class: "grid-tab", type: "button", role: "tab", "aria-controls": panelId, title: labelOf(entry), data: { id: String(entry.id) }, onclick: () => show(entry.id) },
            sprite(entry.material),
            h("span", { class: "grid-tab-name", text: labelOf(entry) }),
            typeof entry.count === "number" ? h("span", { class: "grid-tab-count num", text: num(entry.count) }) : null
          )
        )
      );
    }
    select(state.container);
    railMoved();
  }

  /** Marks the container shown: the one tab the Tab key stops on. */
  function select(id) {
    let chosen = null;
    for (const tab of rail.children) {
      const on = tab.dataset.id === String(id);
      tab.setAttribute("aria-selected", String(on));
      tab.tabIndex = on ? 0 : -1;
      if (on) chosen = tab;
    }
    reveal(rail, chosen);
  }

  function drawHead() {
    const entry = data.shown;
    const tools = (spec.containerActions || [])
      .filter((action) => action.kind !== "pick" && applies(action, entry))
      .map((action) =>
        h(
          "button",
          {
            class: "btn btn-xs btn-row",
            type: "button",
            data: { tone: STYLE_TONE[action.style] || null },
            onclick: async () => {
              if (await runAction(action, entry, { context: here(), target: labelOf(entry), mark })) load();
            },
          },
          icon(glyphOf(action)),
          t(action.labelKey)
        )
      );
    const toolbar = tools.length ? h("div", { class: "grid-tools" }, tools) : null;
    if (toolbar) toolbar.inert = Boolean(picking);
    swap(head, h("h2", { class: "card-title", id: titleId }, sprite(entry.material), h("span", { text: labelOf(entry) })), toolbar);
  }

  function drawHint() {
    if (hintFor === picking) return;
    hintFor = picking;
    hint.hidden = !picking;
    if (!picking) return swap(hint);
    swap(
      hint,
      icon("move"),
      h("p", { text: t("grid-pick-hint", { action: picking.label, target: picking.name }) }),
      h("button", { class: "btn btn-xs btn-quiet", type: "button", onclick: stopPicking }, t("cancel"))
    );
  }

  /** What a cell shows of its item: the icon, how many, and the two lines under it. */
  function itemOf(row) {
    const amount = amountOf(row);
    return [
      h("span", { class: "slot-icon" }, sprite(detailsOf(row).material, 48), amount > 1 ? h("span", { class: "slot-amount num", text: num(amount) }) : null),
      said(row.label) ? h("span", { class: "slot-label", text: said(row.label) }) : null,
      said(row.sub) ? h("span", { class: "slot-sub", text: said(row.sub) }) : null,
    ];
  }

  const position = (slot) => ({ row: Math.floor(slot / COLUMNS) + 1, column: (slot % COLUMNS) + 1 });

  /** A cell in words, for who does not see it: the item, how many, its two lines, where it is. */
  function spoken(row, slot) {
    const amount = amountOf(row);
    const name = itemName(detailsOf(row)) + (amount > 1 ? ` ×${num(amount)}` : "");
    return [name, said(row.label), said(row.sub), t("grid-position", position(slot))].filter(Boolean).join(", ");
  }

  function slotNode(slot) {
    const row = data.slots.get(slot) || null;
    const tone = row && TONES.has(row.tone) ? row.tone : null;
    const press = (label, run, extra, children) => h("button", { class: "slot", type: "button", "aria-label": label, data: { slot, tone, ...extra }, onclick: run }, children);

    // Kept for something else — the bar the game draws along the bottom: what is there shows, dimmed.
    if (data.locked.has(slot)) return h("span", { class: "slot", data: { slot, locked: true }, title: t("grid-reserved"), "aria-hidden": "true" }, row ? itemOf(row) : null);

    if (picking) {
      const origin = picking.from.container === state.container && picking.from.page === data.page && picking.from.slot === slot;
      if (origin) return h("span", { class: "slot", data: { slot, origin: true, tone } }, row ? itemOf(row) : null);
      return press(t("grid-pick-here", { action: picking.label, ...position(slot) }), () => drop(slot), { target: true }, row ? itemOf(row) : null);
    }

    if (row) return attachTip(press(spoken(row, slot), () => openCell(row), null, itemOf(row)), detailsOf(row));

    // An empty cell invites a click only for a reader the form was sent to.
    if (spec.form) return press(t("grid-add-here", { action: t(spec.form.titleKey), ...position(slot) }), () => addAt(slot), { empty: true }, icon("plus"));
    return h("span", { class: "slot", data: { slot, empty: true }, "aria-hidden": "true" });
  }

  function drawCells() {
    const size = data.rows * COLUMNS;
    const nodes = [];
    for (let slot = 0; slot < size; slot++) {
      const row = data.slots.get(slot);
      const origin = picking && picking.from.container === state.container && picking.from.page === data.page && picking.from.slot === slot;
      const signature = `${picking ? `${origin ? "from" : "to"} ${picking.label}` : "open"}|${data.locked.has(slot)}|${row ? JSON.stringify(row) : ""}`;
      let made = built.get(slot);
      if (!made || made.signature !== signature) {
        made = { signature, node: slotNode(slot) };
        built.set(slot, made);
      }
      nodes.push(made.node);
    }
    for (const slot of [...built.keys()]) if (slot >= size) built.delete(slot);
    swap(inv, nodes);
    inv.toggleAttribute("data-picking", Boolean(picking));
    rove();
  }

  /**
   * One cell answers the Tab key: the one the arrows were last on — or, when that one takes no
   * click any more, the nearest that does.
   */
  function rove() {
    const slots = [...inv.querySelectorAll("button.slot")].map((button) => Number(button.dataset.slot));
    if (!slots.includes(cursor)) {
      cursor = slots.length ? slots.reduce((best, slot) => (Math.abs(slot - cursor) < Math.abs(best - cursor) ? slot : best)) : -1;
    }
    for (const button of inv.querySelectorAll("button.slot")) button.tabIndex = Number(button.dataset.slot) === cursor ? 0 : -1;
  }

  function draw() {
    if (!data.shown) {
      rail.hidden = true;
      swap(card, emptyState(t(spec.emptyKey || "no-data-found"), "grid-3x3"));
      return;
    }
    // Where the focus was: what is redrawn takes it back, so the keyboard does not lose its place.
    const within = inv.contains(document.activeElement);
    const paging = pagerBox.contains(document.activeElement);
    if (head.parentNode !== card) swap(card, head, hint, stage, pagerBox);
    rail.inert = Boolean(picking);
    bar.inert = Boolean(picking);
    drawRail();
    drawHead();
    drawHint();
    drawCells();
    swap(
      pagerBox,
      data.pages > 1
        ? pager(data.page, data.pages, (number) => {
            state.page = number;
            load();
          })
        : null
    );
    if (within) focusCursor();
    else if (paging) {
      const current = pagerBox.querySelector('[aria-current="page"]');
      if (current) current.focus({ preventScroll: true });
    }
  }

  function focusCursor() {
    const button = inv.querySelector(`button.slot[data-slot="${cursor}"]`) || inv.querySelector("button.slot");
    if (button) button.focus({ preventScroll: true });
  }

  // ---------------------------------------------------------------------------
  // Doing
  // ---------------------------------------------------------------------------

  function show(id) {
    if (String(id) === state.container) return;
    state.container = String(id);
    state.page = 1;
    cursor = -1;
    select(id);
    load();
  }

  /** A cell that holds something: what it holds, and what can be done with it. */
  async function openCell(row) {
    const details = detailsOf(row);
    const name = itemName(details);
    const amount = amountOf(row);
    const offered = (spec.actions || []).filter((action) => applies(action, row));
    const facts = Object.keys(row).filter((key) => !DRAWN.has(key) && row[key] !== null && row[key] !== "" && typeof row[key] !== "object");
    // More than a name — enchantments, a lore — reads as the game's own tooltip, which a phone has no hover for.
    const rich = (details.enchantments && details.enchantments.length) || (details.lore && details.lore.length) || details.customName;

    const chosen = await openPane({
      title: name,
      lead: sprite(details.material, 48),
      sub: [amount > 1 ? `×${num(amount)}` : "", said(row.label), said(row.sub)].filter(Boolean).join(" · "),
      mark,
      build: (close) => [
        rich ? h("div", { class: "mc-tip mc-tip-inline" }, tooltipFor(details)) : null,
        facts.length
          ? h(
              "div",
              { class: "kv" },
              facts.map((key) => h("div", { class: "kv-row" }, h("span", { class: "kv-key", text: fieldLabel(key) }), h("span", { class: "kv-value" }, cell(key, row[key], false))))
            )
          : null,
        offered.length
          ? h(
              "div",
              { class: "pane-actions" },
              offered.map((action, index) =>
                h(
                  "button",
                  { class: "btn btn-sm btn-secondary glass glass-set", type: "button", autofocus: index === 0 || null, data: { tone: STYLE_TONE[action.style] || null }, onclick: () => close(action) },
                  icon(glyphOf(action)),
                  t(action.labelKey)
                )
              )
            )
          : null,
      ],
    });
    if (!chosen) return;
    if (chosen.kind === "pick") return startPicking(chosen, row, name);
    if ((await runAction(chosen, row, { context: here(), target: name, mark })) && chosen.reload !== false) load();
  }

  /** An empty cell: the page's creation form, sent with where the new item goes. */
  async function addAt(slot) {
    const form = spec.form;
    const label = t(form.titleKey);
    const spot = [labelOf(data.shown), data.pages > 1 ? t("page-number", { page: num(data.page) }) : "", t("grid-position", position(slot))].filter(Boolean).join(" · ");
    const sent = await openDialog({
      title: label,
      text: spot,
      fields: fieldsOf(form.fields),
      confirmLabel: label,
      tone: form.style === "danger" ? "danger" : "",
      onConfirm: (values) => send(form.endpoint, { ...values, ...where(), slot }),
    });
    if (sent === null) return;
    toast(t("action-completed-successfully"), "success");
    cursor = slot;
    load();
  }

  /** "Move": the request is ready but for where to; the next cell clicked says. */
  function startPicking(action, row, name) {
    picking = {
      action,
      label: t(action.labelKey),
      name,
      body: { ...here(), ...bodyFor(action.body, row) },
      from: { container: state.container, page: data.page, slot: row.slot },
    };
    // The cell that was clicked is no button while it is being moved: the nearest one takes over.
    cursor = row.slot;
    draw();
    focusCursor();
  }

  function stopPicking() {
    if (!picking) return;
    const from = picking.from;
    picking = null;
    if (from.container === state.container && from.page === data.page) cursor = from.slot;
    draw();
    focusCursor();
  }

  async function drop(slot) {
    if (!picking || sending) return;
    const move = picking;
    sending = true;
    card.setAttribute("data-busy", "");
    try {
      await send(move.action.endpoint, { ...move.body, to_page: data.page, to_slot: slot });
    } catch (error) {
      // Refused — the cell is taken, the row is kept: the reader may try another.
      toast(error.message, "error");
      card.removeAttribute("data-busy");
      return;
    } finally {
      sending = false;
    }
    picking = null;
    cursor = slot;
    toast(t("action-completed-successfully"), "success");
    draw();
    load();
  }

  // ---------------------------------------------------------------------------
  // Keys
  // ---------------------------------------------------------------------------

  inv.addEventListener("focusin", (event) => {
    const button = event.target.closest("button.slot");
    if (!button) return;
    cursor = Number(button.dataset.slot);
    rove();
  });

  inv.addEventListener("keydown", (event) => {
    const from = event.target.closest("button.slot");
    if (!from || !data) return;
    const at = Number(from.dataset.slot);
    const line = Math.floor(at / COLUMNS);
    const steps = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -COLUMNS, ArrowDown: COLUMNS, Home: 1, End: -1 };
    const step = steps[event.key];
    if (!step) return;
    event.preventDefault();
    const sideways = Math.abs(step) === 1;
    // Home and End go to the ends of the row, then look inwards for a cell that takes a click.
    let next = event.key === "Home" ? line * COLUMNS : event.key === "End" ? line * COLUMNS + COLUMNS - 1 : at + step;
    for (; next >= 0 && next < data.rows * COLUMNS; next += step) {
      if (sideways && Math.floor(next / COLUMNS) !== line) break;
      const node = inv.children[next];
      if (node && node.tagName === "BUTTON") {
        node.focus();
        break;
      }
    }
  });

  rail.addEventListener("keydown", (event) => {
    const tabs = [...rail.children];
    const at = tabs.indexOf(event.target.closest(".grid-tab"));
    if (at < 0) return;
    const moves = { ArrowLeft: at - 1, ArrowUp: at - 1, ArrowRight: at + 1, ArrowDown: at + 1, Home: 0, End: tabs.length - 1 };
    if (!(event.key in moves)) return;
    event.preventDefault();
    const next = tabs[Math.min(tabs.length - 1, Math.max(0, moves[event.key]))];
    next.focus();
    reveal(rail, next);
  });

  /** Escape gives up a move — unless something is open over the page, which Escape closes first. */
  const onKey = (event) => {
    if (event.key === "Escape" && picking && !document.querySelector("dialog[open]")) stopPicking();
  };
  document.addEventListener("keydown", onKey);

  // Minecraft's names land a moment after the first page: the cells say them again.
  const forget = onItemNames(() => {
    if (!data || !data.shown) return;
    built.clear();
    draw();
  });

  return {
    el,
    load,
    destroy() {
      ticket++;
      document.removeEventListener("keydown", onKey);
      forget();
    },
  };
}

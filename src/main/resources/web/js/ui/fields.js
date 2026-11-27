// The inputs of a form, by what each one asks for.
//
// A page declares its forms in Java (WebPageSpec.Field). A field is a text box unless it says
// otherwise, and since 2.1 it can be a number with its bounds, a switch, a list of choices —
// known in advance or asked of the server —, one of the server's currencies, one of its items
// picked by name with its icon, or several lines of text. openDialog (ui/overlay.js) builds each
// one here and only ever asks it the same few things: what it reads, whether it is empty, what is
// wrong with it, and where the focus goes.
//
// Whatever the input looks like, what it reads is text, as the endpoint expects it: "true" for a
// switch that is on, STONE for an item, the id of a currency.

import { h, icon, swap, setVar, debounce } from "../lib/dom.js";
import { get } from "../lib/api.js";
import { t, num, lang, itemName, hasMinecraft } from "../lib/i18n.js";
import { sprite, materialLabel } from "./table.js";

/**
 * @typedef {object} Control
 * @property {Node|Node[]} el          What goes under the label.
 * @property {HTMLElement} target      The element the label names, the focus goes to and an error marks.
 * @property {() => string} read       The value to send.
 * @property {() => boolean} blank     Whether the reader left it empty.
 * @property {() => string|null} [check] What is wrong with a value that is there, in words.
 * @property {boolean} [beside]        Set when it sits beside its label rather than under it.
 * @property {() => void} [destroy]    Lets go of what it put outside its own nodes.
 */

/** Whatever a row or a default holds, as the text an input starts on. */
const textOf = (value) => (value === null || value === undefined ? "" : Array.isArray(value) ? value.join("\n") : String(value));

// ---------------------------------------------------------------------------
// Text and numbers
// ---------------------------------------------------------------------------

/**
 * A plain input. Its type is the field's own — text, number, anything <input> takes, as 2.0
 * allowed — and a number keeps to its bounds: the form says so in words instead of sending a
 * value the endpoint would refuse.
 */
function inputControl(field, id) {
  const numeric = field.type === "number";
  const input = h("input", {
    class: "input",
    id,
    name: field.key,
    type: field.type || "text",
    placeholder: field.placeholder || "",
    required: field.required || null,
    autocomplete: "off",
    spellcheck: false,
    min: numeric ? field.min ?? null : null,
    max: numeric ? field.max ?? null : null,
    // Without a declared step any decimal goes: the browser's own default is whole numbers only.
    step: numeric ? field.step ?? "any" : null,
    // A phone's number pad has no minus sign: it is only asked for where no value can be negative.
    inputmode: numeric && field.min >= 0 ? (Number.isInteger(field.step) ? "numeric" : "decimal") : null,
    value: textOf(field.value),
  });

  const check = () => {
    const state = input.validity;
    if (state.badInput) return t("number-invalid", { field: field.label });
    const low = typeof field.min === "number";
    const high = typeof field.max === "number";
    if (state.rangeUnderflow || state.rangeOverflow) {
      const bounds = { field: field.label, min: low ? num(field.min) : "", max: high ? num(field.max) : "" };
      return t(low && high ? "number-between" : low ? "number-min" : "number-max", bounds);
    }
    if (state.stepMismatch) {
      return field.step === 1 ? t("number-whole", { field: field.label }) : t("number-step", { field: field.label, step: num(field.step, { maximumFractionDigits: 8 }) });
    }
    return null;
  };

  return {
    el: input,
    target: input,
    read: () => input.value.trim(),
    // Letters typed where a number goes read as nothing at all: that is not an empty field.
    blank: () => input.value.trim() === "" && !input.validity.badInput,
    check: numeric ? check : null,
  };
}

/** Several lines, sent as they are: a list of commands, a lore. */
function textareaControl(field, id) {
  const area = h("textarea", {
    class: "input textarea",
    id,
    name: field.key,
    rows: 4,
    placeholder: field.placeholder || "",
    required: field.required || null,
    autocomplete: "off",
    autocapitalize: "off",
    spellcheck: false,
  });
  area.value = textOf(field.value);
  return { el: area, target: area, read: () => area.value, blank: () => area.value.trim() === "" };
}

// ---------------------------------------------------------------------------
// Switches
// ---------------------------------------------------------------------------

const isOn = (value) => value === true || ["true", "1", "yes", "on"].includes(String(value).toLowerCase());

/** A switch. It is never empty: off is an answer. */
function toggleControl(field, id) {
  const input = h("input", { class: "switch", type: "checkbox", role: "switch", id, name: field.key, checked: isOn(field.value) });
  return { el: input, target: input, beside: true, read: () => String(input.checked), blank: () => false };
}

// ---------------------------------------------------------------------------
// Choices
// ---------------------------------------------------------------------------

/**
 * Reads the choices a page asks the server for (optionsFrom): the array its dataKey names, or the
 * first one found, each entry read through its valueField and labelField. A list of plain words
 * is its own values.
 *
 * @param {{endpoint: string, dataKey: string, valueField: string, labelField: string}} source
 * @returns {Promise<Array<{value: string, label: string}>>}
 */
export async function loadOptions(source) {
  const data = await get(source.endpoint);
  let rows = [];
  if (Array.isArray(data)) rows = data;
  else if (data && typeof data === "object") {
    const named = source.dataKey ? data[source.dataKey] : null;
    rows = Array.isArray(named) ? named : Object.values(data).find(Array.isArray) || [];
  }
  const options = [];
  for (const row of rows) {
    const entry = row !== null && typeof row === "object";
    const value = entry ? row[source.valueField] : row;
    if (value === null || value === undefined || value === "") continue;
    const label = entry && source.labelField ? row[source.labelField] : null;
    options.push({ value: String(value), label: label === null || label === undefined || label === "" ? String(value) : String(label) });
  }
  return options;
}

let currencies = null;
let currenciesAt = 0;

/**
 * The server's currencies, each shown with a sum written in it, and the one prices are in when
 * nothing says otherwise. Kept a minute: a form is opened many times over, the list changes with
 * a configuration.
 */
function loadCurrencies() {
  if (!currencies || Date.now() - currenciesAt > 60_000) {
    currenciesAt = Date.now();
    currencies = get("/api/currencies")
      .then((data) => ({
        options: (data && Array.isArray(data.currencies) ? data.currencies : [])
          .filter((currency) => currency && typeof currency.id === "string" && currency.id)
          .map((currency) => ({ value: currency.id, label: currency.sample ? `${currency.id} · ${currency.sample}` : currency.id })),
        preferred: data && typeof data.default === "string" ? data.default : "",
      }))
      .catch((error) => {
        currencies = null;
        throw error;
      });
  }
  return currencies;
}

/**
 * A list of choices. `load`, when the choices come from the server, resolves to them and to the
 * one to start on when nothing else decides.
 *
 * What the row holds stays a choice even when the list no longer has it — a shop since deleted —
 * and is what the field reads until the list has arrived: a form must not change a value by being
 * opened, nor clear one by being sent too soon. For the same reason the server's preferred choice
 * only fills a field that has to be answered: one that may be left empty starts empty, which for a
 * currency means "the usual one", not "this one, written down".
 */
function selectControl(field, id, load) {
  const start = textOf(field.value);
  const select = h("select", { class: "select", id, name: field.key, required: field.required || null });
  const note = h("p", { class: "field-note", role: "alert", hidden: true });
  let ready = !load;

  const fill = (options, preferred = "") => {
    const first = start !== "" ? start : field.required ? preferred : "";
    swap(
      select,
      field.required && first !== "" ? null : h("option", { value: "", text: field.placeholder || t(field.required ? "choose" : "not-set") }),
      first !== "" && !options.some((option) => option.value === first) ? h("option", { value: first, text: first }) : null,
      options.map((option) => h("option", { value: option.value, text: option.label }))
    );
    select.value = first;
  };

  if (load) {
    swap(select, h("option", { value: start, text: t("loading") }));
    select.setAttribute("aria-busy", "true");
    load()
      .then(
        ({ options, preferred }) => fill(options, preferred),
        () => {
          fill([]);
          note.textContent = t("choices-failed");
          note.hidden = false;
        }
      )
      .finally(() => {
        ready = true;
        select.removeAttribute("aria-busy");
      });
  } else fill(field.options || []);

  const read = () => (ready ? select.value : start);
  return { el: [select, note], target: select, read, blank: () => read() === "" };
}

// ---------------------------------------------------------------------------
// Items
// ---------------------------------------------------------------------------

/** How many results the picker lists at once: an icon is only asked for what is listed. */
const ITEM_LIMIT = 40;

let items = null;

/** Every item of the server (/api/items), asked once and kept: the list only changes with the game's version. */
function loadItems() {
  if (!items) {
    items = get("/api/items")
      .then((data) => (data && Array.isArray(data.items) ? data.items : []).filter((item) => item && typeof item.id === "string" && /^[a-z0-9_]{1,64}$/.test(item.id)))
      .catch((error) => {
        items = null;
        throw error;
      });
  }
  return items;
}

/** Lower case, accents off: "epee" finds "Épée en diamant". */
const fold = (text) => String(text).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();

let index = null;
let indexNamed = false;

/**
 * The items as they are searched: each with its name in the reader's language, sorted by it.
 * Built once — and once more when Minecraft's own names arrive, a moment after the first page.
 */
function itemIndex(list) {
  if (index && indexNamed === hasMinecraft()) return index;
  indexNamed = hasMinecraft();
  index = list.map((item) => {
    const name = itemName({ translationKey: item.key, material: item.id });
    return { id: item.id, name, words: fold(name), plain: item.id.replace(/_/g, " ") };
  });
  const collator = new Intl.Collator(lang());
  index.sort((a, b) => collator.compare(a.name, b.name));
  return index;
}

/**
 * The items matching what was typed, best first. Every word typed must be in the name or in the
 * id; a name that starts with it leads, then one with a word that does, then any other, and an
 * item found by its id alone comes last. Between equals, the shorter name: "Stone" before "Stone
 * Bricks".
 */
function searchItems(all, query) {
  const tokens = fold(query).split(/[\s_]+/).filter(Boolean);
  if (tokens.length === 0) return { shown: all.slice(0, ITEM_LIMIT), total: all.length };
  const found = [];
  for (const item of all) {
    let rank = 0;
    for (const token of tokens) {
      const at = item.words.indexOf(token);
      if (at < 0 && !item.plain.includes(token)) {
        rank = -1;
        break;
      }
      rank += at === 0 ? 0 : at > 0 && /[\s'’-]/.test(item.words[at - 1]) ? 1 : at > 0 ? 2 : 3;
    }
    if (rank >= 0) found.push([rank, item]);
  }
  found.sort((a, b) => a[0] - b[0] || a[1].name.length - b[1].name.length);
  return { shown: found.slice(0, ITEM_LIMIT).map(([, item]) => item), total: found.length };
}

/** A material as the picker keeps it: the id, in lower case, or nothing. */
function itemId(value) {
  const id = textOf(value).trim().toLowerCase().replace(/^minecraft:/, "");
  return /^[a-z0-9_]{1,64}$/.test(id) ? id : "";
}

/**
 * One of the server's items: a box to type in, and under it the items whose name or id matches,
 * each with its icon. The arrows walk the list, Enter takes the one lit, Escape closes the list
 * and leaves the form open. What is chosen shows with its icon; emptied, the field reads empty.
 *
 * The list floats over the form rather than inside it: a dialog scrolls, and a list inside it
 * would be cut off at its edge. It is put in the dialog itself — the page behind a modal dialog
 * cannot be reached — and placed by transform under the box, or over it when there is more room
 * there.
 */
function itemControl(field, id) {
  let chosen = itemId(field.value);
  let open = false;
  let typed = false;
  let results = [];
  let active = -1;
  let ticket = 0;
  let host = null;
  /**
   * What the results listed are the answer to. A key pressed before they have caught up with what
   * was typed acts on the answer to all of it: at once when the items are in, or, for Enter, once
   * they have arrived.
   */
  let answered = null;
  let waiting = false;
  let loaded = null;
  const listId = `${id}-list`;
  const nodes = new Map();

  const slot = h("span", { class: "picker-slot" });
  const input = h("input", {
    class: "input",
    id,
    name: field.key,
    type: "text",
    role: "combobox",
    "aria-autocomplete": "list",
    "aria-expanded": "false",
    "aria-haspopup": "listbox",
    placeholder: field.placeholder || t("item-search"),
    required: field.required || null,
    autocomplete: "off",
    autocapitalize: "off",
    spellcheck: false,
  });
  const clear = h("button", { class: "search-clear", type: "button", "aria-label": t("item-clear"), title: t("item-clear"), hidden: true }, icon("x"));
  const box = h("div", { class: "picker" }, slot, input, clear);

  const listbox = h("div", { class: "picker-options", id: listId, role: "listbox", "aria-label": field.label });
  const note = h("p", { class: "picker-note", hidden: true });
  const pop = h("div", { class: "picker-pop" }, listbox, note);

  /** Shows what is chosen: its icon and its name, or the look of an empty search. */
  function paint() {
    swap(slot, chosen ? sprite(chosen) : icon("search"));
    input.value = chosen ? materialLabel(chosen) : "";
    clear.hidden = !chosen;
    // Still in the box: the name is selected whole again, so the next key typed replaces it.
    if (document.activeElement === input) input.select();
  }

  function say(message) {
    note.textContent = message;
    note.hidden = !message;
  }

  /** Puts the list under the box, or over it when the room is there: the list never leaves the screen. */
  function place() {
    if (!open || !host) return;
    const anchor = box.getBoundingClientRect();
    const frame = host.getBoundingClientRect();
    // On a phone the keyboard covers the bottom of the page: what is left is the visual viewport.
    const view = window.visualViewport;
    const top = view ? view.offsetTop : 0;
    const bottom = view ? view.offsetTop + view.height : window.innerHeight;
    const below = bottom - anchor.bottom - 12;
    const above = anchor.top - top - 12;
    const up = below < 176 && above > below;
    setVar(pop, "w", `${Math.round(anchor.width)}px`);
    setVar(pop, "room", `${Math.round(Math.max(96, Math.min(320, up ? above : below)))}px`);
    const x = anchor.left - frame.left - host.clientLeft;
    const y = (up ? anchor.top - 6 : anchor.bottom + 6) - frame.top - host.clientTop;
    pop.style.transform = `translate3d(${Math.round(x)}px, ${Math.round(y)}px, 0)${up ? " translateY(-100%)" : ""}`;
  }

  /** Marks the option the keys are on, for the eye and for a screen reader, and keeps it in view. */
  function mark(follow = true) {
    [...listbox.children].forEach((node, at) => node.setAttribute("aria-selected", String(at === active)));
    const current = listbox.children[active];
    if (!current) {
      input.removeAttribute("aria-activedescendant");
      return;
    }
    input.setAttribute("aria-activedescendant", current.id);
    if (!follow) return;
    const head = current.offsetTop;
    const foot = head + current.offsetHeight;
    if (head < listbox.scrollTop) listbox.scrollTop = head;
    else if (foot > listbox.scrollTop + listbox.clientHeight) listbox.scrollTop = foot - listbox.clientHeight;
  }

  function option(item, at) {
    // An option listed before keeps its node, and with it an icon already fetched — unless that
    // icon was still being waited for when the option left the list: it would wait for ever.
    let node = nodes.get(item.id);
    if (!node || node.querySelector(".sprite[data-pending]")) {
      node = h(
        "div",
        { class: "picker-option", role: "option", data: { id: item.id } },
        sprite(item.id),
        h("span", { class: "picker-name", text: item.name }),
        h("span", { class: "picker-id", text: item.id })
      );
      nodes.set(item.id, node);
    }
    node.id = `${listId}-${at}`;
    return node;
  }

  async function search() {
    const mine = ++ticket;
    let list;
    try {
      list = await loadItems();
    } catch {
      if (mine !== ticket || !open) return;
      results = [];
      swap(listbox);
      say(t("items-failed"));
      return;
    }
    if (mine !== ticket || !open) return;
    loaded = list;
    answer(list);
  }
  const searchSoon = debounce(search, 90);

  /** Lists the items matching what is typed, the best one lit. */
  function answer(list) {
    answered = typed ? input.value : "";
    const found = searchItems(itemIndex(list), answered);
    results = found.shown;
    // Typing lights the best match, so Enter takes it; a list merely opened lights nothing.
    active = typed && results.length ? 0 : -1;
    if (waiting) {
      waiting = false;
      if (results[active]) return choose(results[active]);
    }
    swap(listbox, results.map(option));
    listbox.scrollTop = 0;
    const more = found.total - results.length;
    say(results.length === 0 ? t("item-none") : more > 0 ? t("item-more", { count: num(more) }) : "");
    mark();
    place();
  }

  /** Whether the list shown answers something typed since; brought up to date when it can be at once. */
  function behind() {
    if (!typed || answered === input.value) return false;
    if (!loaded) return true;
    searchSoon.cancel();
    ticket++;
    answer(loaded);
    return false;
  }

  function show() {
    if (open) return;
    open = true;
    host = input.closest("dialog");
    // In a dialog the list floats over it; anywhere else it simply follows the box.
    if (host) host.append(pop);
    else box.after(pop);
    pop.toggleAttribute("data-floating", Boolean(host));
    input.setAttribute("aria-expanded", "true");
    input.setAttribute("aria-controls", listId);
    if (!items) say(t("loading"));
    window.addEventListener("resize", place);
    if (window.visualViewport) window.visualViewport.addEventListener("resize", place);
    if (host) host.addEventListener("scroll", place, { capture: true, passive: true });
    place();
    search();
  }

  function hide() {
    if (!open) return;
    open = false;
    typed = false;
    active = -1;
    answered = null;
    waiting = false;
    ticket++;
    searchSoon.cancel();
    pop.remove();
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-controls");
    input.removeAttribute("aria-activedescendant");
    window.removeEventListener("resize", place);
    if (window.visualViewport) window.visualViewport.removeEventListener("resize", place);
    if (host) host.removeEventListener("scroll", place, { capture: true });
    host = null;
  }

  function choose(item) {
    chosen = item.id;
    hide();
    paint();
    input.removeAttribute("aria-invalid");
  }

  // Reached by the keyboard or by a click, the name is selected whole: typing replaces it.
  input.addEventListener("focus", () => input.select());
  input.addEventListener("click", () => {
    if (open) return;
    show();
    input.select();
  });
  input.addEventListener("input", () => {
    // What is typed is a search, not yet a choice: the icon says so until one is made.
    if (!typed) swap(slot, icon("search"));
    typed = true;
    if (open) searchSoon();
    else show();
  });
  input.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) return show();
      if (behind() || results.length === 0) return;
      const step = event.key === "ArrowDown" ? 1 : -1;
      active = active < 0 ? (step > 0 ? 0 : results.length - 1) : Math.min(results.length - 1, Math.max(0, active + step));
      mark();
    } else if (event.key === "Enter" && open) {
      // The list is open: Enter answers it, and never sends the form by accident. Typed faster
      // than the list follows, it takes the best match of what was typed once that is known.
      event.preventDefault();
      if (behind()) waiting = true;
      else if (results[active]) choose(results[active]);
    } else if (event.key === "Escape" && open) {
      // Escape closes the list first; the dialog only on the next one.
      event.preventDefault();
      event.stopPropagation();
      hide();
      paint();
    }
  });
  input.addEventListener("blur", (event) => {
    if (pop.contains(event.relatedTarget)) return;
    // Emptied and left, the field reads empty. Anything else typed and not chosen is forgotten.
    if (typed && input.value.trim() === "") chosen = "";
    hide();
    paint();
  });

  // Pressing in the list must not take the focus from the box: the list would close under the pointer.
  pop.addEventListener("pointerdown", (event) => event.preventDefault());
  pop.addEventListener("mousedown", (event) => event.preventDefault());
  listbox.addEventListener("pointermove", (event) => {
    const at = [...listbox.children].indexOf(event.target.closest(".picker-option"));
    if (at < 0 || at === active) return;
    active = at;
    mark(false);
  });
  listbox.addEventListener("click", (event) => {
    const at = [...listbox.children].indexOf(event.target.closest(".picker-option"));
    if (results[at]) choose(results[at]);
  });
  clear.addEventListener("click", () => {
    chosen = "";
    hide();
    paint();
    input.focus();
  });

  paint();

  return {
    el: box,
    target: input,
    read: () => chosen.toUpperCase(),
    blank: () => chosen === "",
    destroy: hide,
  };
}

// ---------------------------------------------------------------------------

/**
 * Builds the input of one field.
 *
 * @param {object} field The field: { key, label, type, placeholder, required, value, min, max,
 *   step, options: [{ value, label }], optionsFrom }.
 * @param {string} id    The id its label points at.
 * @returns {Control}
 */
export function control(field, id) {
  if (field.type === "toggle") return toggleControl(field, id);
  if (field.type === "select") return selectControl(field, id, field.optionsFrom ? () => loadOptions(field.optionsFrom).then((options) => ({ options })) : null);
  if (field.type === "currency") return selectControl(field, id, loadCurrencies);
  if (field.type === "item") return itemControl(field, id);
  if (field.type === "textarea") return textareaControl(field, id);
  return inputControl(field, id);
}

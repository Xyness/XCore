// Rows of data, however a module shapes them.
//
// A module sends rows keyed for machines and the table works out how to show each field: a player
// becomes a face and a link, a sanction a badge, a moment "5 min ago" with the exact time on
// hover, an item its icon and the game's own tooltip. Nothing here knows which addon sent what.
//
// On a narrow screen — a phone, or a card too thin for its columns — the same table reads as a
// stack of cards: a container query in css/pages.css rearranges it, and each cell carries its
// column name for that.

import { h, icon, swap, hashOf } from "../lib/dom.js";
import {
  t,
  fieldLabel as labelOf,
  looksTemporal,
  valueLabel,
  num,
  fixed,
  relative,
  dateTime,
  parseDate,
  isDateString,
  itemName,
  hasMinecraftKey,
  normalizeKey,
} from "../lib/i18n.js";
import { playerHref } from "../lib/modules.js";
import { latest } from "../lib/metrics.js";

/** Whether a player is connected right now, from the last figures the panel sent. */
function isOnline(name) {
  const metrics = latest();
  const online = metrics && Array.isArray(metrics.online_players) ? metrics.online_players : null;
  if (!online) return false;
  const wanted = String(name).toLowerCase();
  return online.some((entry) => String(entry).toLowerCase() === wanted);
}

// ---------------------------------------------------------------------------
// Faces
// ---------------------------------------------------------------------------

/**
 * A player's face, fetched by the panel itself (/api/head) so the browser only ever talks to the
 * server it is already talking to. Until it arrives — or if it never does — a monogram on a tint
 * picked by the name stands in.
 */
export function face(name, size = 24) {
  const label = String(name || "?");
  const box = h("span", {
    class: "face",
    "aria-hidden": "true",
    vars: { tint: `var(--tint-${hashOf(label.toLowerCase()) % 6})`, size: `${size}px` },
    text: label.replace(/[^a-z0-9]/gi, "").charAt(0).toUpperCase() || "?",
  });
  if (/^[A-Za-z0-9_]{1,16}$/.test(label)) {
    const img = h("img", { alt: "", width: size, height: size, loading: "lazy", decoding: "async" });
    img.addEventListener("load", () => img.setAttribute("data-ready", ""), { once: true });
    img.addEventListener("error", () => img.remove(), { once: true });
    img.src = `/api/head/${encodeURIComponent(label)}.png`;
    box.append(img);
  }
  return box;
}

export function playerLink(name) {
  const online = isOnline(name);
  return h(
    "a",
    { class: "who", href: playerHref(name), data: { online: online || null } },
    face(name),
    h("span", { class: "who-name", text: name }),
    online ? h("span", { class: "sr-only", text: ` (${t("online")})` }) : null
  );
}

// ---------------------------------------------------------------------------
// Cells
// ---------------------------------------------------------------------------

const PLAYER_KEYS = new Set([
  "player_name", "playername", "player", "seller", "seller_name", "buyer", "buyer_name", "sender",
  "sender_name", "target", "target_name", "reporter", "reported", "staff", "staff_name", "issuer",
  "operator", "moderator", "by", "banned_by", "muted_by", "warned_by", "kicked_by", "owner",
  "high_bidder", "highest_bidder", "bidder", "reporter_name", "reported_name", "moderator_name",
  "added_by", "author", "actor", "staff_member",
]);

/** Values in a player column that are not players: who acts when no one does. */
const NOT_PLAYERS = new Set(["server", "console", "-", "system", "?"]);

/** Whether a column holds player names, so each cell can be a link to the profile. */
export const isPlayerColumn = (key) => PLAYER_KEYS.has(String(key).toLowerCase()) || PLAYER_KEYS.has(normalizeKey(key));

/** Sanction and transaction codes, and how loud each should be. */
const ACTION_TONES = {
  BUY: "success", PURCHASE: "success", BOUGHT: "success", SELL: "danger", SOLD: "danger",
  BAN: "danger", TEMPBAN: "danger", IPBAN: "danger", KICK: "danger",
  MUTE: "warning", TEMPMUTE: "warning", IPMUTE: "warning",
  WARN: "accent", REPORT: "info", JAIL: "purple", FREEZE: "info",
  UNBAN: "success", UNMUTE: "success", UNWARN: "success", UNIPBAN: "success", UNIPMUTE: "success", UNJAIL: "success",
  BAN_IP: "danger", IP_BAN: "danger", BAN_ALTS: "danger", MUTE_IP: "warning", IP_MUTE: "warning",
  UNBAN_IP: "success", UNMUTE_IP: "success", NOTE: "neutral", UNFREEZE: "success",
  DEPOSIT: "success", WITHDRAW: "warning", TRANSFER: "info", PAY: "info", SET: "accent",
  EXPIRED: "neutral", CANCELLED: "neutral", ACTIVE: "success",
};

const LISTING_TYPES = { FIXED_PRICE: ["success", "buy-now"], BUY_NOW: ["success", "buy-now"], AUCTION: ["purple", "auction"] };

const SERVER_KEYS = new Set(["server", "server_name"]);
const MATERIAL_KEYS = new Set(["material", "item_id", "item_material", "item_type", "itemtype"]);
const EXPIRY_KEYS = new Set(["expiration", "expires", "expire_date", "end_date", "duration", "expires_at", "until"]);

export function badge(text, tone, iconName) {
  return h("span", { class: "badge", data: { tone: tone && tone !== "neutral" ? tone : null } }, iconName ? icon(iconName) : null, text);
}

const isPermanent = (value) =>
  value === null || value === undefined || value === "" || value === -1 || value === "-1" ||
  ["none", "null", "permanent", "never"].includes(String(value).toLowerCase());

/** A moment, as a reader thinks of it, with the exact time one hover away. */
export function when(value) {
  const d = parseDate(value);
  if (!d) return document.createTextNode(String(value));
  return h("time", { datetime: d.toISOString(), title: dateTime(d), text: relative(d) });
}

/**
 * How one field of a row is shown. Returns a node; `compact` is true inside a table, where the
 * column heading already names the field and a state can be a mark alone.
 */
export function cell(key, value, compact = true) {
  const lower = String(key).toLowerCase();

  if (typeof value === "string" && value !== "" && isPlayerColumn(key)) {
    if (NOT_PLAYERS.has(value.toLowerCase()) || !/^[A-Za-z0-9_]{1,16}$/.test(value)) {
      return h("span", { class: "who who-system" }, h("span", { class: "face face-system", "aria-hidden": "true" }, icon("server")), h("span", { class: "who-name", text: value }));
    }
    return playerLink(value);
  }

  if ((lower === "action" || lower === "type" || lower === "punishment_type" || lower === "sanction") && value) {
    const code = String(value).toUpperCase().replace(/[\s-]+/g, "_");
    if (ACTION_TONES[code]) return badge(valueLabel(value) || valueLabel(code) || String(value).toUpperCase(), ACTION_TONES[code]);
  }

  if ((lower === "listing_type" || lower === "listingtype" || lower === "sale_type") && value) {
    const known = LISTING_TYPES[String(value).toUpperCase().replace(/ /g, "_")];
    if (known) return badge(t(known[1]), known[0]);
  }

  if (lower === "active" || lower === "status") {
    const state = String(value).toLowerCase();
    if (["true", "1", "active", "yes"].includes(state)) return badge(t("active"), "success");
    if (["false", "0", "expired", "no", "inactive"].includes(state)) return badge(t("expired"), "neutral");
  }

  if (EXPIRY_KEYS.has(lower) && isPermanent(value)) return badge(t("permanent"), "danger");

  if (MATERIAL_KEYS.has(lower) && typeof value === "string" && value) return materialCell(value);

  if (SERVER_KEYS.has(lower) && (value === null || value === undefined || value === "" || value === "null" || value === "global")) {
    return badge(t("global"), "info", "globe");
  }

  if (value === null || value === undefined || value === "") return h("span", { class: "nil", text: "—" });

  if (typeof value === "boolean") {
    const word = t(value ? "label-yes" : "label-no");
    return h(
      "span",
      { class: "flag", data: { on: value || null }, title: word },
      icon(value ? "check" : "x"),
      compact ? h("span", { class: "sr-only", text: word }) : h("span", { text: word })
    );
  }

  if (typeof value === "number") {
    if (looksTemporal(key) && value > 1e9) return when(value);
    return document.createTextNode(Number.isInteger(value) ? num(value) : fixed(value, 2));
  }

  const text = String(value);
  if (isDateString(text)) return when(text);

  const named = valueLabel(text);
  if (named) return document.createTextNode(named);

  if (lower.includes("uuid")) return h("span", { class: "uuid", title: text, text });

  // A reason nobody gave is stored as the word "none".
  if (lower === "reason" && text.toLowerCase() === "none") return h("span", { class: "nil", text: "—" });

  // Addresses kept as one string, "a;b;c", read as a list.
  if (/^[\d.:a-f]+(;[\d.:a-f]+)+$/i.test(text)) return document.createTextNode(text.split(";").join(", "));

  if (lower === "reason" || text.length > 60) return h("span", { class: "long", title: text, text });

  return document.createTextNode(text);
}

// ---------------------------------------------------------------------------
// Items
// ---------------------------------------------------------------------------

const ROMAN = [[10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];

function roman(value) {
  if (!(value > 0 && value <= 10)) return String(value);
  let rest = value;
  let out = "";
  for (const [amount, letters] of ROMAN) {
    while (rest >= amount) {
      out += letters;
      rest -= amount;
    }
  }
  return out;
}

/** An item's 16px texture, served and cached by the panel; a material no mirror has gets a tile. */
export function sprite(material) {
  const box = h("span", { class: "sprite", "aria-hidden": "true" });
  if (material && /^[a-z0-9_]{1,64}$/i.test(String(material))) {
    const img = h("img", { alt: "", width: 32, height: 32, loading: "lazy", decoding: "async" });
    img.addEventListener("error", () => {
      img.remove();
      box.setAttribute("data-missing", "");
    }, { once: true });
    img.src = `/api/sprite/${encodeURIComponent(String(material).toLowerCase())}.png`;
    box.append(img);
  } else {
    box.setAttribute("data-missing", "");
  }
  return box;
}

const tips = new WeakMap();
const materials = new WeakMap();

/**
 * A bare material — a market row, a stock line — drawn like an item: its icon and its name in
 * the reader's language, tried as an item and then as a block, as the game keys them.
 */
function materialCell(material) {
  const label = h("span", { class: "item-name" }, materialLabel(material));
  const node = h("span", { class: "item" }, sprite(material), label);
  materials.set(node, material);
  return node;
}

function materialLabel(material) {
  const id = String(material).toLowerCase();
  return itemName({ translationKey: hasMinecraftKey(`item.minecraft.${id}`) ? `item.minecraft.${id}` : `block.minecraft.${id}`, material });
}

export function itemCell(details) {
  if (!details || !details.material) return h("span", { class: "nil", text: "—" });
  const name = itemName(details);
  const enchanted = Array.isArray(details.enchantments) && details.enchantments.length > 0;
  const rarity = details.customName ? "custom" : enchanted ? "enchanted" : null;
  const node = h(
    "span",
    { class: "item", tabindex: "0", data: { tip: true } },
    sprite(details.material),
    h(
      "span",
      { class: "item-name", data: { rarity } },
      name,
      details.amount > 1 ? h("span", { class: "item-amount", text: ` ×${num(details.amount)}` }) : null
    )
  );
  tips.set(node, details);
  return node;
}

/**
 * Swaps the item names already on the page for Minecraft's own, once the panel has fetched them:
 * they arrive after the first page is drawn, and redrawing the page would lose its search.
 */
export function refreshItemNames() {
  for (const node of document.querySelectorAll(".item")) {
    const details = tips.get(node);
    const material = materials.get(node);
    const label = node.querySelector(".item-name");
    if (!label || !label.firstChild || label.firstChild.nodeType !== Node.TEXT_NODE) continue;
    if (details) label.firstChild.nodeValue = itemName(details);
    else if (material) label.firstChild.nodeValue = materialLabel(material);
  }
}

/** The game's tooltip for an item: name, enchantments, lore, durability, amount. */
function tooltipFor(details) {
  const enchanted = Array.isArray(details.enchantments) && details.enchantments.length > 0;
  const lines = [
    h("p", {
      class: "mc-name",
      data: { rarity: details.customName ? "custom" : enchanted ? "enchanted" : null },
      text: itemName(details),
    }),
  ];
  for (const enchant of details.enchantments || []) {
    lines.push(h("p", { class: "mc-enchant", text: `${enchant.name} ${roman(enchant.level)}` }));
  }
  for (const line of details.lore || []) if (line) lines.push(h("p", { class: "mc-lore", text: line }));
  if (details.damage !== null && details.damage !== undefined && details.maxDurability) {
    const remaining = details.maxDurability - details.damage;
    lines.push(h("p", { class: "mc-meta", text: `${t("durability")}${remaining}/${details.maxDurability}` }));
  }
  if (details.amount > 1) lines.push(h("p", { class: "mc-meta", text: `${t("amount")}${details.amount}` }));
  return lines;
}

/**
 * One tooltip for the whole page, fixed to the viewport: an item sits in a table that scrolls
 * sideways, and a tooltip inside it would be cut off by the scroll box.
 */
let tip = null;
let tipFor = null;

function showTip(target) {
  const details = tips.get(target);
  if (!details) return;
  if (!tip) {
    tip = h("div", { class: "mc-tip", role: "tooltip", id: "mc-tip" });
    document.body.append(tip);
  }
  tipFor = target;
  swap(tip, tooltipFor(details));
  target.setAttribute("aria-describedby", "mc-tip");
  tip.removeAttribute("data-on");
  const box = target.getBoundingClientRect();
  const width = tip.offsetWidth;
  const height = tip.offsetHeight;
  const x = Math.min(Math.max(8, box.left), window.innerWidth - width - 8);
  const above = box.top - height - 8;
  const y = above > 8 ? above : Math.min(box.bottom + 8, window.innerHeight - height - 8);
  tip.style.transform = `translate3d(${Math.round(x)}px, ${Math.round(y)}px, 0)`;
  requestAnimationFrame(() => tip && tipFor === target && tip.setAttribute("data-on", ""));
}

function hideTip(target) {
  if (!tip || (target && tipFor !== target)) return;
  tip.removeAttribute("data-on");
  if (tipFor) tipFor.removeAttribute("aria-describedby");
  tipFor = null;
}

document.addEventListener("pointerover", (event) => {
  const target = event.target.closest && event.target.closest("[data-tip]");
  if (target && target !== tipFor) showTip(target);
  else if (!target && tipFor && event.pointerType === "mouse") hideTip();
});
document.addEventListener("focusin", (event) => {
  const target = event.target.closest && event.target.closest("[data-tip]");
  if (target) showTip(target);
  else hideTip();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") hideTip();
});
window.addEventListener("scroll", () => hideTip(), { passive: true, capture: true });

// ---------------------------------------------------------------------------
// Tables
// ---------------------------------------------------------------------------

/**
 * A table of rows.
 *
 * options:
 *   actions   [{ label, tone, run(row, button) }] — a button per row each
 *   caption   an accessible name for the table
 */
/**
 * The columns of a set of rows: every field any of them has, in the order they first appear. The
 * first row alone is not enough — an auction carries bid fields a fixed-price listing does not.
 * A field that holds an object or a list has nothing a cell can show, except the item, which gets
 * a column of its own.
 */
function columnsOf(rows) {
  const seen = new Map();
  for (const row of rows.slice(0, 200)) {
    for (const key in row) {
      if (key === "itemDetails") continue;
      const value = row[key];
      if (!seen.has(key)) seen.set(key, value === null || value === undefined ? null : typeof value === "object");
      else if (seen.get(key) === null && value !== null && value !== undefined) seen.set(key, typeof value === "object");
    }
  }
  return [...seen].filter(([, isObject]) => !isObject).map(([key]) => key);
}

export function dataTable(rows, { actions = [], caption = "", columns = null } = {}) {
  const withItem = rows.some((row) => row && row.itemDetails && typeof row.itemDetails === "object");
  // A page that knows its columns (the players list) names them itself.
  const labels = new Map((columns || []).map((column) => [column.key, column.label]));
  const fieldLabel = (key) => labels.get(key) || labelOf(key);
  const keys = columns ? columns.map((column) => column.key) : columnsOf(rows);
  const numeric = new Set(keys.filter((key) => !looksTemporal(key) && rows.some((row) => typeof row[key] === "number")));

  // The cell that says who or what a row is about — the item, else the first player — leads the
  // row: first in the table, where it stays in view while a wide table scrolls sideways, and the
  // title of each card on a narrow screen.
  const lead = withItem ? "itemDetails" : keys.find((key) => isPlayerColumn(key)) || keys[0];
  if (!withItem && lead !== keys[0]) {
    keys.splice(keys.indexOf(lead), 1);
    keys.unshift(lead);
  }

  const head = h(
    "tr",
    null,
    withItem ? h("th", { scope: "col", text: t("item") }) : null,
    keys.map((key) => h("th", { scope: "col", class: numeric.has(key) ? "is-num" : null, text: fieldLabel(key) })),
    actions.length ? h("th", { scope: "col", class: "is-actions" }, h("span", { class: "sr-only", text: t("actions") })) : null
  );

  const body = h("tbody");
  for (const row of rows) {
    const tr = h("tr");
    if (withItem) tr.append(h("td", { class: "is-lead", data: { label: t("item") } }, itemCell(row.itemDetails)));
    for (const key of keys) {
      tr.append(
        h(
          "td",
          {
            class: [numeric.has(key) ? "is-num" : "", key === lead ? "is-lead" : ""].join(" ").trim() || null,
            data: { label: fieldLabel(key) },
          },
          cell(key, row[key])
        )
      );
    }
    if (actions.length) {
      const holder = h("div", { class: "row-actions" });
      for (const action of actions) {
        if (action.when && !action.when(row)) continue;
        const button = h("button", {
          class: "btn btn-xs btn-row",
          type: "button",
          data: { tone: action.tone || null },
          text: action.label,
        });
        button.addEventListener("click", () => action.run(row, button));
        holder.append(button);
      }
      tr.append(h("td", { class: "is-actions" }, holder));
    }
    body.append(tr);
  }

  const wrap = h(
    "div",
    { class: "table-wrap" },
    h(
      "table",
      { class: "data" },
      caption ? h("caption", { class: "sr-only", text: caption }) : null,
      h("thead", null, head),
      body
    )
  );
  watchOverflow(wrap);
  return wrap;
}

/**
 * A table wider than its card scrolls sideways inside it, and a table cut clean at the card's
 * edge does not say so: the side it continues past fades out (data-more), as on the site.
 */
function watchOverflow(wrap) {
  let frame = 0;
  const update = () => {
    frame = 0;
    const more = [];
    if (wrap.scrollLeft > 2) more.push("start");
    if (wrap.scrollLeft + wrap.clientWidth < wrap.scrollWidth - 2) more.push("end");
    if (more.length) wrap.dataset.more = more.join(" ");
    else delete wrap.dataset.more;
  };
  const schedule = () => {
    if (!frame) frame = requestAnimationFrame(update);
  };
  wrap.addEventListener("scroll", schedule, { passive: true });
  new ResizeObserver(schedule).observe(wrap);
}

/** Rows that stand in for a table while it loads. */
export function skeletonRows(count = 6) {
  return h(
    "div",
    { class: "skel-rows", "aria-hidden": "true" },
    Array.from({ length: count }, (_, index) =>
      h(
        "div",
        { class: "skel-row", vars: { w: `${60 + ((index * 37) % 35)}%` } },
        h("span", { class: "skel" }),
        h("span", { class: "skel" }),
        h("span", { class: "skel" }),
        h("span", { class: "skel" })
      )
    )
  );
}

export function emptyState(message, iconName = "list") {
  return h("div", { class: "state" }, h("span", { class: "state-icon" }, icon(iconName)), h("p", { text: message }));
}

export function errorState(message, retry) {
  return h(
    "div",
    { class: "state", role: "alert" },
    h("span", { class: "state-icon", data: { tone: "danger" } }, icon("circle-alert")),
    h("p", { text: message }),
    retry
      ? h("button", { class: "btn btn-sm btn-secondary glass glass-set", type: "button", onclick: retry }, icon("refresh-cw"), t("retry"))
      : null
  );
}

/**
 * Page numbers: previous, a window of five around the current page, the last one, next. On a
 * phone, only previous, "3 / 12" and next.
 */
export function pager(page, pages, go) {
  const button = (label, target, { current = false, iconName = null, labelHidden = false } = {}) =>
    h(
      "button",
      {
        class: `btn btn-sm btn-quiet pager-btn${current ? " glass glass-set" : ""}${iconName ? " btn-icon" : ""}`,
        type: "button",
        "aria-current": current ? "page" : null,
        "aria-label": labelHidden ? label : null,
        disabled: target < 1 || target > pages || null,
        onclick: () => go(target),
      },
      iconName ? icon(iconName) : label
    );

  let start = Math.max(1, page - 2);
  const end = Math.min(pages, start + 4);
  start = Math.max(1, end - 4);
  const numbers = [];
  if (start > 1) {
    numbers.push(button("1", 1));
    if (start > 2) numbers.push(h("span", { class: "pager-gap", "aria-hidden": "true", text: "…" }));
  }
  for (let index = start; index <= end; index++) numbers.push(button(String(index), index, { current: index === page }));
  if (end < pages) {
    if (end < pages - 1) numbers.push(h("span", { class: "pager-gap", "aria-hidden": "true", text: "…" }));
    numbers.push(button(String(pages), pages));
  }

  return h(
    "nav",
    { class: "pager", "aria-label": t("pagination") },
    button(t("previous"), page - 1, { iconName: "chevron-left", labelHidden: true }),
    h("div", { class: "pager-numbers" }, numbers),
    h("span", { class: "pager-compact num", text: t("page-of", { page: num(page), total: num(pages) }) }),
    button(t("next"), page + 1, { iconName: "chevron-right", labelHidden: true })
  );
}

// Words and numbers.
//
// The strings come from /api/lang, in the language set by `language` in XCore's config.yml, so
// the dashboard follows the same single setting as the game messages. Each addon ships the words
// of its own pages in its lang/web_<code>.yml and the server merges them in. Numbers and dates
// are formatted for that same language, so a French page does not read "Nov 18, 2026".

let strings = {};
let minecraft = {};

export function setStrings(next) {
  strings = next && typeof next === "object" ? next : {};
  formatters.clear();
  document.documentElement.lang = lang();
}

/** The language of the strings, as a BCP 47 tag ("en", "fr"). */
export function lang() {
  const code = strings["language-code"];
  return typeof code === "string" && /^[a-z]{2,3}(-[A-Za-z]{2,4})?$/.test(code) ? code : "en";
}

/**
 * Translates a key, falling back to the key itself, which keeps the interface usable and the
 * missing key visible when a translation file is incomplete. Replacements substitute both {name}
 * and %name%, as the Java side does.
 */
export function t(key, replacements) {
  let value = strings[key];
  if (value === undefined || value === null) value = key;
  value = String(value);
  if (replacements) {
    for (const name in replacements) {
      const raw = replacements[name];
      const text = raw === undefined || raw === null ? "" : String(raw);
      value = value.split(`{${name}}`).join(text).split(`%${name}%`).join(text);
    }
  }
  return value;
}

/** Whether a translation exists — for the few places that word something differently without one. */
export const has = (key) => strings[key] !== undefined && strings[key] !== null;

/** camelCase and snake_case reduced to one form, so a lookup can be written once. */
export const normalizeKey = (key) => String(key).replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();

/** "item_uuid" and "itemUuid" both read "Item Uuid". */
export function capitalize(text) {
  return String(text)
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * The label for a raw JSON field.
 *
 * Module payloads are keyed for machines — player_uuid, warn_count, listed_at. A module names its
 * fields in its own language file under field-<key>; anything it has not named falls back to the
 * prettified key, so a new field is never blank, just untranslated.
 */
export function fieldLabel(key) {
  const normalized = normalizeKey(key);
  return (
    strings[`field-${key}`] ||
    strings[`field-${normalized}`] ||
    strings[key] ||
    strings[normalized] ||
    capitalize(normalized)
  );
}

/** Whether a field holds a moment in time, so a bare number can be read as one. */
export function looksTemporal(key) {
  return /(^|_)(at|date|time|since|expires|expiration|seen|login|created|registered|until|timestamp)($|_)/i.test(
    normalizeKey(key)
  );
}

/** A value the server chose rather than a word: a module names it under value-<identifier>. */
export const valueLabel = (value) => strings[`value-${value}`] || null;

// ---------------------------------------------------------------------------
// Minecraft's own item names
// ---------------------------------------------------------------------------

export function setMinecraft(map) {
  minecraft = map && typeof map === "object" ? map : {};
}

export const hasMinecraft = () => Object.keys(minecraft).length > 0;

export const hasMinecraftKey = (key) => typeof minecraft[key] === "string";

/**
 * The name of an item, in the reader's language when Minecraft has one for it. Falls back to the
 * material name the module formatted, so an item the game does not name — anything a plugin
 * invented — still reads properly.
 */
export function itemName(details) {
  if (details.customName) return details.customName;
  const translated = details.translationKey ? minecraft[details.translationKey] : null;
  return translated || details.displayMaterial || materialName(details.material);
}

export function materialName(value) {
  if (!value || typeof value !== "string") return "—";
  return value.toLowerCase().replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

const formatters = new Map();

function formatter(kind, options) {
  const key = kind + JSON.stringify(options);
  let made = formatters.get(key);
  if (!made) {
    const locale = lang();
    if (kind === "number") made = new Intl.NumberFormat(locale, options);
    else if (kind === "date") made = new Intl.DateTimeFormat(locale, options);
    else made = new Intl.RelativeTimeFormat(locale, options);
    formatters.set(key, made);
  }
  return made;
}

export function num(value, options = {}) {
  if (typeof value !== "number" || !Number.isFinite(value)) return value === null || value === undefined ? "—" : String(value);
  return formatter("number", options).format(value);
}

/** A figure with exactly `digits` decimals: 19.8 TPS, not 19.795. */
export const fixed = (value, digits = 1) =>
  num(value, { minimumFractionDigits: digits, maximumFractionDigits: digits });

/** Bytes, in the unit a person would say: 512 MB, 3.7 GB. */
export function bytes(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  const gb = value / 1024 ** 3;
  if (gb >= 1) return num(gb, { style: "unit", unit: "gigabyte", maximumFractionDigits: 1 });
  return num(Math.round(value / 1024 ** 2), { style: "unit", unit: "megabyte", maximumFractionDigits: 0 });
}

/** How long a server has been up, by its two largest units: 3 d 7 h, 42 min. */
export function duration(seconds) {
  if (typeof seconds !== "number" || !Number.isFinite(seconds) || seconds < 0) return "—";
  const parts = [
    ["day", Math.floor(seconds / 86400)],
    ["hour", Math.floor((seconds % 86400) / 3600)],
    ["minute", Math.floor((seconds % 3600) / 60)],
  ];
  const first = parts.findIndex(([, amount]) => amount > 0);
  if (first < 0) return num(0, { style: "unit", unit: "minute", unitDisplay: "narrow" });
  return parts
    .slice(first, first + 2)
    .filter(([, amount], index) => index === 0 || amount > 0)
    .map(([unit, amount]) => num(amount, { style: "unit", unit, unitDisplay: "narrow" }))
    .join(" ");
}

/**
 * Reads a moment the way modules send one: epoch milliseconds, epoch seconds, or the string a
 * database hands back ("2026-11-18 14:30:00"), which Safari refuses to parse with a space in it.
 */
export function parseDate(value) {
  if (value === null || value === undefined || value === "") return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof value === "number") {
    if (value > 1e11) return new Date(value);
    if (value > 1e9) return new Date(value * 1000);
    return null;
  }
  const text = String(value).trim();
  if (!/^\d{4}-\d{2}-\d{2}/.test(text)) return null;
  const iso = text.replace(/^(\d{4}-\d{2}-\d{2}) (\d)/, "$1T$2");
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

export const isDateString = (value) => typeof value === "string" && /^\d{4}-\d{2}-\d{2}/.test(value) && parseDate(value) !== null;

export function date(value) {
  const d = parseDate(value);
  return d ? formatter("date", { year: "numeric", month: "short", day: "numeric" }).format(d) : String(value ?? "—");
}

export function dateTime(value) {
  const d = parseDate(value);
  return d
    ? formatter("date", { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(d)
    : String(value ?? "—");
}

export function clock(value) {
  const d = parseDate(value);
  return d ? formatter("date", { hour: "2-digit", minute: "2-digit" }).format(d) : String(value ?? "");
}

/**
 * A moment as a reader thinks of it: "5 min ago", "in 3 days" for anything within a week, the
 * date itself beyond. The exact time is always one hover away (see cells in ui/table.js).
 */
export function relative(value, now = Date.now()) {
  const d = parseDate(value);
  if (!d) return String(value ?? "—");
  const seconds = (d.getTime() - now) / 1000;
  const abs = Math.abs(seconds);
  const rtf = formatter("relative", { numeric: "auto", style: "short" });
  if (abs < 45) return rtf.format(0, "second");
  if (abs < 3600) return rtf.format(Math.round(seconds / 60), "minute");
  if (abs < 86400) return rtf.format(Math.round(seconds / 3600), "hour");
  if (abs < 7 * 86400) return rtf.format(Math.round(seconds / 86400), "day");
  return date(d);
}

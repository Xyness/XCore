// What the panel has installed: the modules /api/modules lists, each with the pages it described
// in Java (WebPageSpec). Nothing about a particular addon lives in this code, except how the
// Celestis plugins are recognised by name to wear their colour and icon from the site — any other
// module gets the icon it asked for and the brass.

import { get } from "./api.js";
import { t } from "./i18n.js";

let modules = [];
let loading = null;

export function setModules(list) {
  modules = Array.isArray(list) ? list.filter((mod) => mod && typeof mod.name === "string") : [];
}

export const allModules = () => modules;

/**
 * Forgets the list, on signing out: each session gets the modules its owner's permissions open,
 * and the next one to sign in in this tab must not start from the last one's.
 */
export function forgetModules() {
  modules = [];
  loading = null;
}

/** Loads the module list once; later calls share the same answer. */
export function loadModules() {
  if (!loading) {
    loading = get("/api/modules")
      .then((list) => {
        setModules(list);
        return modules;
      })
      .catch((error) => {
        loading = null;
        throw error;
      });
  }
  return loading;
}

export const findModule = (name) =>
  modules.find((mod) => mod.name === name) || modules.find((mod) => mod.name.toLowerCase() === String(name).toLowerCase()) || null;

export const findPage = (mod, path) => (mod && Array.isArray(mod.pages) ? mod.pages.find((page) => page.path === path) || null : null);

/** The colour and icon of each Celestis plugin, as on celestis.dev — keyed by category there. */
const KNOWN = {
  xbans: { mark: "var(--p-moderation)", icon: "shield-off" },
  xauctionhouse: { mark: "var(--p-economy)", icon: "gavel" },
  xlogin: { mark: "var(--p-security)", icon: "lock" },
  xantilag: { mark: "var(--p-performance)", icon: "zap" },
  xshop: { mark: "var(--p-economy)", icon: "store" },
  // XCore's own economy belongs to the framework.
  economy: { mark: "var(--p-framework)", icon: "coins" },
};

/** Icon names modules use for themselves, their pages and their buttons, mapped onto the bundled set. */
const ICON_ALIASES = {
  shield: "shield",
  "shield-ban": "shield-off",
  ban: "ban",
  hammer: "hammer",
  gavel: "gavel",
  coins: "coins",
  money: "coins",
  wallet: "wallet",
  chart: "chart-column",
  stats: "chart-column",
  "bar-chart": "chart-column",
  "chart-bar": "chart-column",
  activity: "activity",
  lock: "lock",
  key: "key-round",
  users: "users",
  user: "user",
  list: "list",
  history: "history",
  clock: "clock",
  settings: "settings",
  config: "settings",
  cog: "settings",
  gear: "settings",
  zap: "zap",
  cpu: "cpu",
  server: "server",
  database: "database",
  map: "map",
  globe: "globe",
  package: "package",
  box: "box",
  store: "store",
  shop: "store",
  "scroll-text": "scroll-text",
  scroll: "scroll-text",
  "file-text": "file-text",
  flag: "flag",
  report: "flag",
  warning: "triangle-alert",
  alert: "triangle-alert",
  mute: "mic-off",
  "mic-off": "mic-off",
  timer: "timer",
  hourglass: "hourglass",
  wifi: "wifi",
  eye: "eye",
  mail: "mail",
  blocks: "blocks",
  layers: "layers",
  grid: "grid-3x3",
  "grid-3x3": "grid-3x3",
  anchor: "anchor",
  "trending-up": "trending-up",
  trend: "trending-up",
  star: "star",
  snowflake: "snowflake",
  "globe-lock": "globe-lock",
  "globe-mute": "globe",
  "volume-x": "volume-x",
  "alert-triangle": "triangle-alert",
  "triangle-alert": "triangle-alert",
  "message-square": "message-square",
  message: "message-square",
  "clipboard-list": "clipboard-list",
  brain: "brain",
  gauge: "gauge",
  memory: "memory-stick",
  "user-x": "user-x",
  "user-check": "user-check",
  landmark: "landmark",
  banknote: "banknote",
  search: "search",
  trash: "trash-2",
  "trash-2": "trash-2",
  delete: "trash-2",
  edit: "pencil",
  pencil: "pencil",
  rename: "pencil",
  move: "move",
  download: "download",
  export: "download",
  plus: "plus",
  add: "plus",
  check: "check",
  x: "x",
  copy: "copy",
  refresh: "refresh-cw",
  "refresh-cw": "refresh-cw",
  reset: "rotate-ccw",
  "rotate-ccw": "rotate-ccw",
  save: "save",
  info: "info",
  sliders: "sliders-horizontal",
  "sliders-horizontal": "sliders-horizontal",
};

export function iconFor(name, fallback = "puzzle") {
  if (!name) return fallback;
  const key = String(name).toLowerCase();
  return ICON_ALIASES[key] || fallback;
}

/** A module's colour (a CSS value, always a token) and icon. */
export function markOf(mod) {
  const name = mod && mod.name ? mod.name.toLowerCase() : "";
  const known = KNOWN[name];
  if (known) return known;
  return { mark: "var(--accent)", icon: iconFor(mod && mod.icon, "puzzle") };
}

/** What kind of page it is, from its descriptor: decides its icon and its one-word hint. */
export function kindOf(page) {
  const spec = page && page.spec;
  if (!spec) return "list";
  if (spec.type === "stats") return "stats";
  if (spec.type === "config") return "config";
  if (spec.type === "grid") return "grid";
  if (spec.form) return "manage";
  return "list";
}

const KIND_ICON = { stats: "chart-column", config: "settings", grid: "grid-3x3", manage: "list", list: "list" };

export function pageIcon(page) {
  return iconFor(page && page.icon, KIND_ICON[kindOf(page)]);
}

export const kindLabel = (page) => t(`page-kind-${kindOf(page)}`);

/** The label of a page in the navigation: its name as a language key, or its path. */
export const pageName = (page) => t(page.name || page.path);

/**
 * The title of a page. Titles are often written "XBans - Bans" for a sidebar that had no other
 * way to say which addon a page belongs to; here the eyebrow already says it, so the prefix goes.
 */
export function pageTitle(mod, page) {
  const raw = t((page.spec && page.spec.titleKey) || page.name || page.path);
  // "XBans - Bans", "Auction House - Listings": the part before the dash names the module, in
  // its own words or spaced out, when its letters are the module's with or without the X.
  const split = /^(.+?)\s+[-–—:]\s+(.+)$/.exec(raw);
  if (!split) return raw;
  const letters = (text) => text.toLowerCase().replace(/[^a-z0-9]/g, "");
  const named = letters(split[1]);
  const own = letters(mod.name);
  return named === own || named === own.replace(/^x/, "") ? split[2] : raw;
}

export const pageHref = (mod, page) => `#/m/${encodeURIComponent(mod.name)}/${encodeURIComponent(page.path)}`;
export const moduleHref = (mod) => `#/m/${encodeURIComponent(mod.name)}`;
export const playerHref = (name) => `#/player/${encodeURIComponent(name)}`;

// What this session may open.
//
// Every member of staff signs in with their own permissions, read from the game. /api/auth says
// which of the dashboard's they hold, and /api/modules only lists the plugins, pages and buttons
// they can use — the panel refuses the rest whatever the page asks. What is kept here is the
// little the pages need besides: whether the core pages are open, and what exists but is out of
// reach, so that a link to it says so instead of reading "page not found".

const OPEN = Object.freeze({
  player: null,
  permissions: null,
  access: { players: true, config: true },
  locked: { modules: [], pages: {}, needs: {} },
});

let me = OPEN;

const strings = (list) => (Array.isArray(list) ? list.filter((entry) => typeof entry === "string" && entry) : []);
const same = (a, b) => String(a).toLowerCase() === String(b).toLowerCase();

/**
 * What something needs, as the panel says it: a list of requirements, all needed, each met by any
 * one of its nodes — [["a"], ["b", "c"]] reads "a and b or c". A flat list of nodes is one
 * requirement.
 */
export function requirements(value) {
  if (!Array.isArray(value) || value.length === 0) return [];
  if (value.every((entry) => typeof entry === "string")) {
    const anyOf = strings(value);
    return anyOf.length ? [anyOf] : [];
  }
  return value.map(strings).filter((anyOf) => anyOf.length);
}

/** Takes the answer of /api/auth. One that says nothing about permissions leaves everything open. */
export function setAccess(answer) {
  if (!answer || typeof answer !== "object") {
    me = OPEN;
    return;
  }
  const access = answer.access && typeof answer.access === "object" ? answer.access : {};
  const locked = answer.locked && typeof answer.locked === "object" ? answer.locked : {};
  const pages = {};
  if (locked.pages && typeof locked.pages === "object") {
    for (const name in locked.pages) pages[name] = strings(locked.pages[name]);
  }
  const needs = {};
  if (locked.needs && typeof locked.needs === "object") {
    for (const name in locked.needs) {
      const byPage = locked.needs[name];
      if (!byPage || typeof byPage !== "object") continue;
      needs[name] = {};
      for (const path in byPage) needs[name][path] = requirements(byPage[path]);
    }
  }
  me = {
    player: typeof answer.player === "string" && answer.player ? answer.player : null,
    permissions: Array.isArray(answer.permissions) ? strings(answer.permissions).map((node) => node.toLowerCase()) : null,
    access: { players: access.players !== false, config: access.config !== false },
    locked: { modules: strings(locked.modules), pages, needs },
  };
}

/** Whose session this is: the player who asked for the link, or null for one tied to nobody. */
export const signedInAs = () => me.player;

/** The players list and the profiles. */
export const canSeePlayers = () => me.access.players;

/** Whether the session holds a permission. A panel that sends no list holds them all. */
export const holds = (node) => !me.permissions || me.permissions.includes(String(node).toLowerCase());

/** The permission that opens a module's pages, named the way the panel names it. */
export const modulePermission = (name) => `xcore.dashboard.${String(name).toLowerCase().replace(/[^a-z0-9_-]/g, "")}`;

/** What a module's actions need when its pages name nothing of their own. */
export const managePermission = (name) => `${modulePermission(name)}.manage`;

/** A module installed but out of this session's reach, by its own name; null when it is not one. */
export const lockedModule = (name) => me.locked.modules.find((entry) => same(entry, name)) || null;

/**
 * Whether a page of a module the session can open is out of its reach: the configuration editor
 * without xcore.dashboard.config, a page showing addresses or accounts without the permission
 * reading it takes.
 */
export function isLockedPage(moduleName, path) {
  const key = Object.keys(me.locked.pages).find((name) => same(name, moduleName));
  return Boolean(key) && me.locked.pages[key].includes(path);
}

/** What a page out of reach needs, every requirement the session misses; empty when the panel did not say. */
export function lockedPageNeeds(moduleName, path) {
  const key = Object.keys(me.locked.needs).find((name) => same(name, moduleName));
  return (key && me.locked.needs[key][path]) || [];
}

// The dashboard starts here.
//
// index.html has already taken the token out of a /xcore dashboard link and started the first
// requests — the strings, the session check, the module list and the figures — so by the time
// this runs most of them are on their way back. What is left is to wait for them, then draw the
// login screen or the shell, and route.
//
// Routes live in the fragment (#/players, #/m/XBans/bans), so the back button, a bookmark and a
// link opened in a new tab all work, on a panel that serves every path the same index.html.
//
// Each reader sees the dashboard their in-game permissions open: /api/auth says which, and a
// route out of their reach — a link someone shared, a bookmark from before a rank changed — opens
// a page that says so, rather than "page not found".

import { h, reducedMotion } from "./lib/dom.js";
import { get, token, whenUnauthorized, whenForbidden, ApiError } from "./lib/api.js";
import { setStrings, t, setMinecraft } from "./lib/i18n.js";
import { loadModules, forgetModules, setModules, allModules, findModule, findPage, markOf, pageTitle } from "./lib/modules.js";
import { setAccess, canSeePlayers, lockedModule, isLockedPage, lockedPageNeeds, modulePermission } from "./lib/access.js";
import { startMetrics } from "./lib/metrics.js";
import { mountShell } from "./ui/shell.js";
import { refreshItemNames } from "./ui/table.js";
import { renderLogin } from "./pages/login.js";
import { homePage } from "./pages/home.js";
import { playersPage, profilePage } from "./pages/players.js";
import { moduleHome, specPage, missingPage, noAccessPage } from "./pages/module.js";

const PLAYERS_PERMISSION = "xcore.dashboard.players";
const CONFIG_PERMISSION = "xcore.dashboard.config";

const app = document.getElementById("app");
let shell = null;
let view = null;
let firstRender = true;

/** Reads the fragment into a route. Unknown fragments open the overview. */
function parseRoute() {
  const hash = window.location.hash.replace(/^#\/?/, "");
  const parts = hash.split("/").map((part) => {
    try {
      return decodeURIComponent(part);
    } catch {
      return part;
    }
  });
  if (parts[0] === "players") return { name: "players" };
  if (parts[0] === "player" && parts[1]) return { name: "player", player: parts[1] };
  if (parts[0] === "m" && parts[1]) return { name: parts[2] ? "page" : "module", module: parts[1], path: parts.slice(2).join("/") };
  return { name: "home" };
}

/** Builds the view for a route: { el, title, href, module, mark, destroy }. */
async function viewFor(route) {
  if ((route.name === "players" || route.name === "player") && !canSeePlayers()) {
    return noAccessPage({ needs: [PLAYERS_PERMISSION] });
  }
  if (route.name === "players") return { ...playersPage(), href: "#/players" };
  if (route.name === "player") return { ...profilePage(route.player), href: "" };
  if (route.name === "module" || route.name === "page") {
    const mod = findModule(route.module);
    if (!mod) {
      // Installed, but not for this reader: the panel names the modules it left out.
      const locked = lockedModule(route.module);
      return locked ? noAccessPage({ mod: { name: locked }, needs: [modulePermission(locked)] }) : missingPage();
    }
    const mark = markOf(mod).mark;
    if (route.name === "module") return { ...moduleHome(mod), module: mod.name, mark, href: `#/m/${encodeURIComponent(mod.name)}` };
    const page = findPage(mod, route.path);
    if (!page) {
      // A page of a module the reader can open may still be closed to them: the configuration
      // editor, or a page showing addresses and accounts. The panel says what it needs.
      if (!isLockedPage(mod.name, route.path)) return missingPage(mod);
      const needs = lockedPageNeeds(mod.name, route.path);
      return noAccessPage({ mod, needs: needs.length ? needs : [CONFIG_PERMISSION] });
    }
    const href = `#/m/${encodeURIComponent(mod.name)}/${encodeURIComponent(page.path)}`;
    if (page.spec) return { ...specPage(mod, page), title: pageTitle(mod, page), module: mod.name, mark, href };
    // A page that does not describe itself gets the generic renderer of old, loaded on demand.
    const { legacyPage } = await import("./pages/legacy.js");
    return { ...legacyPage(mod, page), title: pageTitle(mod, page), module: mod.name, mark, href };
  }
  return { ...homePage(), href: "#/" };
}

let rendering = 0;

async function render() {
  const ticket = ++rendering;
  const route = parseRoute();
  const next = await viewFor(route);
  if (ticket !== rendering) {
    if (next.destroy) next.destroy();
    return;
  }

  const commit = () => {
    if (view && view.destroy) view.destroy();
    view = next;
    shell.main.replaceChildren(next.el);
    shell.setActive(next.href || "", next.module || null);
    shell.setTitle(next.title || "");
    shell.setMark(next.mark || "");
    shell.setSheet(false);
    document.title = next.title ? `${next.title} · ${t("xcore")}` : t("xcore-dashboard");
    window.scrollTo(0, 0);
    // Focus goes to the new page's heading, as it would on a real page load, so a screen reader
    // announces where it is and the next Tab starts from there.
    const heading = next.el.querySelector("h1");
    if (heading && !firstRender) {
      heading.setAttribute("tabindex", "-1");
      heading.focus({ preventScroll: true });
    }
    if (next.mounted) next.mounted();
  };

  if (!firstRender && document.startViewTransition && !reducedMotion()) {
    document.startViewTransition(commit);
  } else {
    commit();
    if (!firstRender) next.el.classList.add("page-in");
  }
  firstRender = false;
}

function showLogin(message) {
  if (shell) {
    shell.destroy();
    shell = null;
  }
  if (view && view.destroy) view.destroy();
  view = null;
  firstRender = true;
  window.removeEventListener("hashchange", render);
  // What this session could open is not what the next one to sign in here may.
  forgetModules();
  setAccess(null);
  document.title = t("xcore-dashboard");
  renderLogin(app, { message, onSuccess: startDashboard });
}

// ---------------------------------------------------------------------------
// Permissions that change while the dashboard is open
// ---------------------------------------------------------------------------

// The panel reads the reader's permissions from the game while they play, so a rank can change
// under an open page. A refusal says so, and so may a tab that sat in the background: either way
// the dashboard asks again what it may show, rebuilds the navigation if it moved, and redraws the
// page only when what it shows changed.

let accessCheck = null;
/** When the dashboard last learned what it may show. */
let accessCheckedAt = 0;

/** What the navigation is built from. */
const navSignature = () =>
  JSON.stringify([canSeePlayers(), allModules().map((mod) => [mod.name, (mod.pages || []).map((page) => page.path)])]);

/** What the current page is built from, so an unrelated change leaves it alone. */
function routeSignature() {
  const route = parseRoute();
  if (route.name === "players" || route.name === "player") return `players:${canSeePlayers()}`;
  if (route.name === "module" || route.name === "page") {
    const mod = findModule(route.module);
    if (!mod) return `locked:${Boolean(lockedModule(route.module))}`;
    if (route.name === "module") return JSON.stringify(mod);
    return JSON.stringify([findPage(mod, route.path), isLockedPage(mod.name, route.path), lockedPageNeeds(mod.name, route.path)]);
  }
  return navSignature();
}

function recheckAccess() {
  // Two seconds apart at most: a page refused on several of its calls asks once.
  if (!shell || accessCheck || Date.now() - accessCheckedAt < 2000) return;
  const nav = navSignature();
  const page = routeSignature();
  accessCheck = Promise.all([get("/api/auth"), get("/api/modules")])
    .then(([me, list]) => {
      if (!shell) return;
      setAccess(me);
      setModules(list);
      if (navSignature() !== nav) shell.buildNav();
      if (routeSignature() !== page) render();
    })
    .catch(() => {})
    .finally(() => {
      accessCheckedAt = Date.now();
      accessCheck = null;
    });
}

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && Date.now() - accessCheckedAt > 60000) recheckAccess();
});

async function startDashboard(me) {
  // From the login screen, which checked the token: its answer says what the session may do.
  if (me) setAccess(me);
  accessCheckedAt = Date.now();
  shell = mountShell(app, () => showLogin());
  try {
    await loadModules();
  } catch (error) {
    // Without the module list the core pages still work; the sidebar simply lists no addon.
    if (error instanceof ApiError && error.status === 401) return;
  }
  shell.buildNav();
  window.addEventListener("hashchange", render);
  await render();
  startMetrics();

  // Minecraft's own item names, half a megabyte the panel fetches from an asset mirror: they land
  // after the first page, and the item names already drawn are swapped in place.
  get("/api/mclang")
    .then((names) => {
      setMinecraft(names);
      refreshItemNames();
    })
    .catch(() => {});
}

async function boot() {
  whenUnauthorized(() => {
    if (!shell) return;
    token.clear();
    showLogin(t("session-expired"));
  });
  whenForbidden(() => recheckAccess());

  try {
    setStrings(await get("/api/lang"));
  } catch {
    setStrings({});
  }
  const skip = document.querySelector(".skip");
  if (skip) skip.textContent = t("skip-to-content");

  if (!token.get()) {
    showLogin();
    return;
  }
  let me;
  try {
    me = await get("/api/auth");
  } catch (error) {
    // Unreachable is not the same as refused: keep the token for the next try.
    if (error instanceof ApiError && error.status !== 0) token.clear();
    showLogin(t(error instanceof ApiError && error.status === 0 ? "invalid-token-or-server-unreachable" : "session-expired"));
    return;
  }
  startDashboard(me);
}

boot().catch((error) => {
  console.error(error);
  app.replaceChildren(h("p", { class: "state", text: String(error && error.message ? error.message : error) }));
});

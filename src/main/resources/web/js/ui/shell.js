// The frame around every page: the sidebar, and on a phone the bar and the sheet.
//
// One navigation serves both. From 1024px it is a panel of glass on the left; below that it slides
// in from the left when the menu button is pressed, and the bar at the top carries the page's name.
// A plugin's group folds away under its name, and which ones are open is remembered across reloads.

import { h, icon, brandMark, swap, setVar } from "../lib/dom.js";
import { attachLens } from "../lib/glass.js";
import { t } from "../lib/i18n.js";
import { token } from "../lib/api.js";
import { allModules, markOf, pageIcon, pageName, moduleHref, pageHref } from "../lib/modules.js";
import { onMetrics, tpsState } from "../lib/metrics.js";

const GROUPS_KEY = "xcore_open_groups";
const THEME_KEY = "xcore-theme";

function openGroups() {
  try {
    return JSON.parse(localStorage.getItem(GROUPS_KEY) || "{}") || {};
  } catch {
    return {};
  }
}

function rememberGroup(name, open) {
  const state = openGroups();
  if (open) state[name] = 1;
  else delete state[name];
  try {
    localStorage.setItem(GROUPS_KEY, JSON.stringify(state));
  } catch {
    // Not remembered: it still works for this visit.
  }
}

/** Switches between light and dark, and remembers it; the system decides until then. */
export function toggleTheme() {
  const next = document.documentElement.dataset.theme === "light" ? "dark" : "light";
  document.documentElement.dataset.theme = next;
  try {
    localStorage.setItem(THEME_KEY, next);
  } catch {
    // The choice lasts as long as the page.
  }
}

function themeButton(extraClass = "") {
  return h(
    "button",
    {
      class: `btn btn-icon glass glass-set ${extraClass}`.trim(),
      type: "button",
      "aria-label": t("theme-toggle"),
      title: t("theme-toggle"),
      onclick: toggleTheme,
    },
    h("span", { class: "when-dark" }, icon("sun")),
    h("span", { class: "when-light" }, icon("moon"))
  );
}

/**
 * Builds the shell into `root` and returns the handles the router needs.
 *
 * @param {HTMLElement} root   Where the shell goes (#app).
 * @param {() => void} onLogout What signing out does once the token is gone.
 */
export function mountShell(root, onLogout) {
  const chipName = h("span", { text: t("server") });
  const dot = h("i", { class: "live-dot" });
  const chip = h("span", { class: "server-chip", title: t("server") }, dot, chipName);

  const nav = h("nav", { class: "nav", "aria-label": t("navigation") });
  const close = h(
    "button",
    { class: "btn btn-sm btn-icon btn-quiet sheet-close", type: "button", "aria-label": t("close-menu"), onclick: () => setSheet(false) },
    icon("x")
  );

  const sidebar = h(
    "aside",
    { class: "sidebar glass", id: "sidebar", "aria-label": t("xcore") },
    h(
      "div",
      { class: "sidebar-head" },
      h("a", { class: "brand", href: "#/" }, brandMark(), h("span", { text: t("xcore") })),
      chip,
      close
    ),
    nav,
    h(
      "div",
      { class: "sidebar-foot" },
      h(
        "button",
        {
          class: "btn btn-sm btn-quiet",
          type: "button",
          onclick: () => {
            token.clear();
            onLogout();
          },
        },
        icon("log-out"),
        t("logout")
      ),
      themeButton("btn-sm")
    )
  );

  const menuButton = h(
    "button",
    {
      class: "btn btn-icon btn-quiet",
      type: "button",
      "aria-label": t("open-menu"),
      "aria-controls": "sidebar",
      "aria-expanded": "false",
      onclick: () => setSheet(true),
    },
    icon("menu")
  );
  const barTitle = h("span", { class: "topbar-title" });
  const topbar = h(
    "header",
    { class: "topbar glass" },
    menuButton,
    barTitle,
    h("a", { class: "brand", href: "#/", "aria-label": t("overview") }, brandMark())
  );

  const scrim = h("div", { class: "scrim", "aria-hidden": "true", onclick: () => setSheet(false) });
  const main = h("main", { class: "main", id: "main", tabindex: "-1" });

  swap(root, h("div", { class: "shell" }, h("div", { class: "edge-fade", "aria-hidden": "true" }), sidebar, topbar, scrim, main));

  const lenses = [attachLens(sidebar), attachLens(topbar)];

  // --- The sheet, under 1024px ------------------------------------------------

  const narrow = window.matchMedia("(max-width: 1023.98px)");
  let sheetOpen = false;

  function setSheet(open) {
    if (!narrow.matches) open = false;
    if (open === sheetOpen) return;
    sheetOpen = open;
    sidebar.toggleAttribute("data-open", open);
    scrim.toggleAttribute("data-open", open);
    menuButton.setAttribute("aria-expanded", String(open));
    // Closed, the sheet is hidden outright, which takes its links out of the tab order; the page
    // behind is inert while it is open, so focus cannot wander under it.
    main.inert = open;
    topbar.inert = open;
    if (open) {
      const current = nav.querySelector('[aria-current="page"]') || nav.querySelector("a");
      requestAnimationFrame(() => (current || close).focus({ preventScroll: true }));
    } else if (sidebar.contains(document.activeElement)) {
      menuButton.focus({ preventScroll: true });
    }
  }

  const syncNarrow = () => {
    if (!narrow.matches) {
      setSheet(false);
      sidebar.removeAttribute("data-open");
      main.inert = false;
      topbar.inert = false;
    }
    // On a phone the sidebar is the sheet: closed, it must not be reachable.
    sidebar.inert = narrow.matches && !sheetOpen;
  };
  narrow.addEventListener("change", syncNarrow);
  const observer = new MutationObserver(syncNarrow);
  observer.observe(sidebar, { attributes: true, attributeFilter: ["data-open"] });
  syncNarrow();

  const onKey = (event) => {
    if (event.key === "Escape" && sheetOpen) setSheet(false);
  };
  document.addEventListener("keydown", onKey);

  // --- What scrolled under the bar -------------------------------------------

  let scrolled = false;
  const onScroll = () => {
    const next = window.scrollY > 12;
    if (next === scrolled) return;
    scrolled = next;
    document.documentElement.toggleAttribute("data-scrolled", next);
  };
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  // --- The server, live ----------------------------------------------------------

  const stopMetrics = onMetrics((m) => {
    if (m.server_name) {
      chipName.textContent = m.server_name;
      chip.title = m.server_name;
    }
    const state = tpsState(m.tps);
    if (state) dot.dataset.state = state;
    else delete dot.dataset.state;
  });

  // --- Navigation ---------------------------------------------------------------

  function link(href, label, iconNode, { sub = false, mark = null } = {}) {
    return h(
      "a",
      { class: `nav-link${sub ? " nav-sub" : ""}`, href, data: { href }, vars: mark ? { mark } : null },
      iconNode,
      h("span", { text: label })
    );
  }

  function buildNav() {
    const open = openGroups();
    const groups = allModules()
      .filter((mod) => Array.isArray(mod.pages) && mod.pages.length > 0)
      .map((mod) => {
        const { mark, icon: iconName } = markOf(mod);
        const id = `nav-${mod.name.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
        const group = h("div", { class: "nav-group", data: { module: mod.name, open: open[mod.name] ? true : null } });
        const caret = h(
          "button",
          {
            class: "nav-caret",
            type: "button",
            "aria-expanded": open[mod.name] ? "true" : "false",
            "aria-controls": id,
            "aria-label": t("show-pages-of", { module: mod.name }),
          },
          icon("chevron-right")
        );
        caret.addEventListener("click", () => setGroup(group, !group.hasAttribute("data-open")));
        group.append(
          h(
            "div",
            { class: "nav-group-head" },
            link(moduleHref(mod), mod.name, h("span", { class: "mark-tile" }, icon(iconName)), { mark }),
            caret
          ),
          h(
            "div",
            { class: "nav-pages", id },
            mod.pages.map((page) => link(pageHref(mod, page), pageName(page), icon(pageIcon(page)), { sub: true, mark }))
          )
        );
        return group;
      });

    swap(
      nav,
      h("p", { class: "nav-title label", text: t("core") }),
      link("#/", t("overview"), icon("layout-grid")),
      link("#/players", t("players"), icon("users")),
      groups.length ? h("p", { class: "nav-title label", text: t("plugins-title") }) : null,
      groups
    );
    if (activeHref) setActive(activeHref);
  }

  function setGroup(group, open) {
    group.toggleAttribute("data-open", open);
    const caret = group.querySelector(".nav-caret");
    if (caret) caret.setAttribute("aria-expanded", String(open));
    rememberGroup(group.dataset.module, open);
  }

  let activeHref = "";

  /** Marks where the reader is, and unfolds the plugin it belongs to. */
  function setActive(href, moduleName = null) {
    activeHref = href;
    for (const anchor of nav.querySelectorAll(".nav-link")) {
      if (anchor.dataset.href === href) anchor.setAttribute("aria-current", "page");
      else anchor.removeAttribute("aria-current");
    }
    if (moduleName) {
      const group = [...nav.querySelectorAll(".nav-group")].find((g) => g.dataset.module === moduleName);
      if (group && !group.hasAttribute("data-open")) setGroup(group, true);
    }
  }

  // --- The light behind the glass -----------------------------------------------

  const ambientLayers = [...document.querySelectorAll(".ambient-mark")];
  let ambientShown = 0;
  let ambientMark = "";

  /** Cross-fades the coloured light behind the page to the colour of the plugin being read. */
  function setMark(mark) {
    const next = mark || "";
    if (next === ambientMark || ambientLayers.length < 2) return;
    ambientMark = next;
    const outgoing = ambientLayers[ambientShown];
    ambientShown = 1 - ambientShown;
    const incoming = ambientLayers[ambientShown];
    setVar(incoming, "mark", next || null);
    outgoing.removeAttribute("data-on");
    if (next) incoming.setAttribute("data-on", "");
  }

  return {
    main,
    buildNav,
    setActive,
    setMark,
    setSheet,
    setTitle(title) {
      barTitle.textContent = title || "";
    },
    destroy() {
      stopMetrics();
      lenses.forEach((undo) => undo());
      observer.disconnect();
      narrow.removeEventListener("change", syncNarrow);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", onScroll);
    },
  };
}

export { themeButton };

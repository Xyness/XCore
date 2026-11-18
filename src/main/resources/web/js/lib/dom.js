// Building elements without a framework.
//
// h() is the one way the dashboard makes DOM. Text goes in as text nodes, never as markup, so a
// player name or a ban reason can never become HTML. The only markup ever parsed is the icon set,
// which is a constant of this code.

import { ICONS } from "./icons.js";

const SVG_NS = "http://www.w3.org/2000/svg";

/** Properties set on the element itself rather than as attributes. */
const PROPS = new Set(["value", "checked", "disabled", "hidden", "selected", "indeterminate", "readOnly", "spellcheck"]);

/**
 * Creates an element.
 *
 *   h("button", { class: "btn", onclick: go, "aria-label": t("logout") }, icon("x"))
 *
 * `vars` sets custom properties ({ mark: "var(--p-security)" } sets --mark) — the only styling a
 * script is allowed to write, together with transforms. `data` sets data-* attributes, `true`
 * meaning present. Children may be strings, nodes, arrays, or null to skip.
 */
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const key in props) {
      const value = props[key];
      if (value === null || value === undefined || value === false) continue;
      if (key === "class") el.className = value;
      else if (key === "text") el.textContent = value;
      else if (key === "vars") for (const name in value) setVar(el, name, value[name]);
      else if (key === "data") {
        for (const name in value) {
          const v = value[name];
          if (v !== null && v !== undefined && v !== false) el.dataset[name] = v === true ? "" : String(v);
        }
      } else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2), value);
      else if (PROPS.has(key)) el[key] = value;
      else el.setAttribute(key, value === true ? "" : String(value));
    }
  }
  append(el, children);
  return el;
}

/** Appends children, flattening arrays and skipping empty ones. */
export function append(el, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false || child === "") continue;
    if (Array.isArray(child)) append(el, child);
    else el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

/** Replaces everything inside an element. */
export function swap(el, ...children) {
  el.replaceChildren();
  return append(el, children);
}

export function setVar(el, name, value) {
  if (value === null || value === undefined) el.style.removeProperty(`--${name}`);
  else el.style.setProperty(`--${name}`, String(value));
}

/**
 * An icon from the set, as inline SVG that takes the text colour. Decorative by default: the
 * control it sits in carries the name.
 */
export function icon(name, className = "") {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("class", className ? `icon ${className}` : "icon");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  svg.innerHTML = ICONS[name] || ICONS.puzzle;
  return svg;
}

/** The Celestis mark: an isometric block, drawn rather than filled — the site's logo. */
export function brandMark(className = "") {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.6");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("aria-hidden", "true");
  if (className) svg.setAttribute("class", className);
  svg.innerHTML = '<path d="M12 2.4 20.3 7v10L12 21.6 3.7 17V7z"/><path d="M3.7 7 12 11.7 20.3 7"/><path d="M12 11.7v9.9"/>';
  return svg;
}

/** Runs `fn` at most once per `wait` ms of quiet. */
export function debounce(fn, wait) {
  let timer = 0;
  const run = (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
  run.cancel = () => clearTimeout(timer);
  return run;
}

/** Whether the user asked for less motion. Read live: the setting can change while a tab is open. */
export const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/** A small stable hash of a string, for picking one of a few tints by name. */
export function hashOf(text) {
  let hash = 7;
  for (const char of String(text)) hash = (Math.imul(hash, 31) + char.codePointAt(0)) | 0;
  return hash >>> 0;
}

/** Copies text, falling back to a hidden field where the Clipboard API is not allowed (plain HTTP). */
export async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Refused: try the old way below.
  }
  const field = h("textarea", { class: "sr-only", readonly: true });
  field.value = text;
  document.body.append(field);
  field.select();
  let done = false;
  try {
    done = document.execCommand("copy");
  } catch {
    done = false;
  }
  field.remove();
  return done;
}

/**
 * Lays a grid of tiles out in even rows on a wide screen: eight tiles read as two rows of four,
 * not a row of six and two left over. Six or fewer keep the grid's own flow.
 */
export function balanceTiles(grid) {
  const count = [...grid.children].filter((child) => !child.hidden).length;
  if (count > 6) {
    grid.dataset.balanced = "";
    setVar(grid, "cols", Math.ceil(count / Math.ceil(count / 6)));
  } else {
    delete grid.dataset.balanced;
    setVar(grid, "cols", null);
  }
}

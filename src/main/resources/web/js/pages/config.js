// The configuration editor: an addon's config.yml, as it is on disk.
//
// Every module gets one (/api/<module>/config/raw), and saving is safe by construction: the panel
// parses the YAML before writing anything, so a typo is refused with the parser's message and the
// addon keeps a file it can load. When that message names a line, the editor takes you there.
//
// It is a plain textarea, so everything a browser does with text — undo, search, spellcheck off,
// a phone's keyboard — keeps working; under it, a copy of the text is drawn in colour. YAML has no
// tabs, so Tab indents by two spaces; Escape, then Tab, leaves the editor as usual.

import { h, icon, swap } from "../lib/dom.js";
import { get, post, moduleBase } from "../lib/api.js";
import { t } from "../lib/i18n.js";
import { toast, openDialog } from "../ui/overlay.js";
import { errorState } from "../ui/table.js";

const INDENT = "  ";

/** Splits one line of YAML into coloured runs. Good enough to read by, never to parse by. */
function paintLine(line) {
  const out = [];
  let rest = line;
  const comment = /(^|\s)#.*$/.exec(rest);
  let tail = null;
  if (comment && !/^[^#]*["'][^"']*#/.test(rest)) {
    tail = rest.slice(comment.index + comment[1].length);
    rest = rest.slice(0, comment.index + comment[1].length);
  }
  const key = /^(\s*)(- )?([^\s:#'"][^:#]*?|"[^"]*"|'[^']*')(:)(?=\s|$)/.exec(rest);
  if (key) {
    out.push(key[1]);
    if (key[2]) out.push(["dash", key[2]]);
    out.push(["key", key[3]], ["punct", key[4]]);
    rest = rest.slice(key[0].length);
  } else {
    const dash = /^(\s*)(- )/.exec(rest);
    if (dash) {
      out.push(dash[1], ["dash", dash[2]]);
      rest = rest.slice(dash[0].length);
    }
  }
  const value = rest.trim();
  if (value) {
    const lead = rest.slice(0, rest.indexOf(value));
    const trail = rest.slice(rest.indexOf(value) + value.length);
    let kind = "plain";
    if (/^(["']).*\1$/.test(value)) kind = "string";
    else if (/^(true|false|yes|no|on|off|null|~)$/i.test(value)) kind = "bool";
    else if (/^[-+]?(\d[\d_]*)(\.\d+)?([eE][-+]?\d+)?$/.test(value)) kind = "number";
    else if (/^[|>][-+]?$/.test(value)) kind = "punct";
    out.push(lead, [kind, value], trail);
  } else if (rest) out.push(rest);
  if (tail) out.push(["comment", tail]);
  return out;
}

function paint(view, text) {
  const fragment = document.createDocumentFragment();
  const lines = text.split("\n");
  lines.forEach((line, index) => {
    for (const part of paintLine(line)) {
      if (typeof part === "string") {
        if (part) fragment.append(part);
      } else {
        fragment.append(h("span", { class: `tok-${part[0]}`, text: part[1] }));
      }
    }
    if (index < lines.length - 1) fragment.append("\n");
  });
  // A final empty line still needs its height in the copy, or the caret drifts past the end.
  fragment.append("\n");
  view.replaceChildren(fragment);
}

export function configEditor(name) {
  const base = `${moduleBase(name)}/config/raw`;
  let saved = "";
  let errorLine = 0;

  const input = h("textarea", {
    class: "code-input",
    spellcheck: false,
    autocapitalize: "off",
    autocomplete: "off",
    wrap: "off",
    "aria-label": t("config-editor-label", { addon: name }),
    "aria-describedby": "config-hint",
  });
  const view = h("pre", { class: "code-view", "aria-hidden": "true" });
  const gutter = h("div", { class: "code-gutter", "aria-hidden": "true" });
  const gutterInner = h("div", { class: "code-gutter-inner" });
  gutter.append(gutterInner);
  const status = h("p", { class: "config-status", role: "status" });
  const save = h("button", { class: "btn glass-brass", type: "button", disabled: true }, icon("save"), h("span", { text: t("save-reload") }));
  const revert = h("button", { class: "btn btn-secondary glass glass-set", type: "button" }, icon("rotate-ccw"), h("span", { text: t("revert-reload-from-disk") }));

  const editor = h("div", { class: "code" }, gutter, h("div", { class: "code-area" }, view, input));
  const parts = [
    h("p", { class: "config-hint", id: "config-hint", text: t("config-editor-hint", { addon: name }) }),
    editor,
    h(
      "div",
      { class: "config-bar" },
      h("p", { class: "config-keys label", text: t("config-editor-keys") }),
      status,
      h("div", { class: "config-actions" }, revert, save)
    ),
  ];
  const card = h("section", { class: "card config-card" }, parts);
  const el = h("div", { class: "config" }, card);

  let painted = 0;
  const repaint = () => {
    painted = 0;
    paint(view, input.value);
    const count = input.value.split("\n").length;
    if (gutterInner.childElementCount !== count) {
      swap(
        gutterInner,
        Array.from({ length: count }, (_, index) => h("span", { data: { line: index + 1 }, text: String(index + 1) }))
      );
    }
    markError(errorLine);
  };
  const schedule = () => {
    if (!painted) painted = requestAnimationFrame(repaint);
  };

  /** The copy and the numbers follow the textarea's own scroll, by transform alone. */
  const follow = () => {
    view.style.transform = `translate3d(${-input.scrollLeft}px, ${-input.scrollTop}px, 0)`;
    gutterInner.style.transform = `translate3d(0, ${-input.scrollTop}px, 0)`;
  };

  const dirty = () => input.value !== saved;
  const sync = () => {
    save.disabled = !dirty();
    if (dirty() && !status.hasAttribute("data-error")) status.textContent = t("unsaved-changes");
    else if (!dirty() && !status.hasAttribute("data-error")) status.textContent = "";
  };

  function markError(line) {
    errorLine = line;
    for (const node of gutterInner.querySelectorAll("[data-error]")) node.removeAttribute("data-error");
    if (line > 0) {
      const node = gutterInner.querySelector(`[data-line="${line}"]`);
      if (node) node.setAttribute("data-error", "");
    }
  }

  function goToLine(line) {
    const lines = input.value.split("\n");
    if (line < 1 || line > lines.length) return;
    const start = lines.slice(0, line - 1).reduce((sum, text) => sum + text.length + 1, 0);
    input.focus();
    input.setSelectionRange(start, start + lines[line - 1].length);
    const lineHeight = parseFloat(getComputedStyle(input).lineHeight) || 20;
    input.scrollTop = Math.max(0, (line - 4) * lineHeight);
    follow();
  }

  // Tab and Shift+Tab indent and outdent the selected lines; Escape lets the next Tab leave.
  let released = false;
  input.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
      event.preventDefault();
      if (!save.disabled) save.click();
      return;
    }
    if (event.key === "Escape") {
      released = true;
      return;
    }
    if (event.key !== "Tab") {
      released = false;
      return;
    }
    if (released) {
      released = false;
      return;
    }
    event.preventDefault();
    const { selectionStart: start, selectionEnd: end, value } = input;
    const lineStart = value.lastIndexOf("\n", start - 1) + 1;
    if (!event.shiftKey && start === end) {
      input.setRangeText(INDENT, start, end, "end");
    } else {
      const block = value.slice(lineStart, end);
      const changed = event.shiftKey
        ? block.replace(/^ {1,2}/gm, "")
        : block.replace(/^/gm, INDENT);
      input.setRangeText(changed, lineStart, end, "select");
    }
    input.dispatchEvent(new Event("input"));
  });
  input.addEventListener("input", () => {
    schedule();
    if (status.hasAttribute("data-error")) {
      status.removeAttribute("data-error");
      markError(0);
    }
    sync();
  });
  input.addEventListener("scroll", follow, { passive: true });

  const beforeUnload = (event) => {
    if (dirty()) {
      event.preventDefault();
      event.returnValue = "";
    }
  };
  window.addEventListener("beforeunload", beforeUnload);

  save.addEventListener("click", async () => {
    save.disabled = true;
    revert.disabled = true;
    status.removeAttribute("data-error");
    status.textContent = t("saving");
    const text = input.value;
    try {
      await post(base, { yaml: text });
      saved = text;
      status.textContent = "";
      markError(0);
      toast(t("config-saved-and-reloaded", { addon: name }), "success");
    } catch (error) {
      const message = error.message || t("save-failed");
      status.setAttribute("data-error", "");
      swap(status, icon("circle-alert"), h("span", { text: message }));
      const line = /line (\d+)/i.exec(message);
      if (line) {
        markError(Number(line[1]));
        swap(
          status,
          icon("circle-alert"),
          h("span", { text: message }),
          h("button", { class: "btn btn-xs btn-quiet", type: "button", onclick: () => goToLine(Number(line[1])) }, t("go-to-line", { line: line[1] }))
        );
      }
      toast(t("save-failed-see-message-below-the-editor"), "error");
    } finally {
      revert.disabled = false;
      sync();
    }
  });

  revert.addEventListener("click", async () => {
    if (dirty()) {
      const ok = await openDialog({ title: t("revert-reload-from-disk"), text: t("discard-changes"), confirmLabel: t("revert-reload-from-disk"), tone: "danger" });
      if (ok === null) return;
    }
    load();
  });

  async function load() {
    revert.disabled = true;
    save.disabled = true;
    try {
      const data = await get(base);
      saved = (data && typeof data.yaml === "string" ? data.yaml : "");
      input.value = saved;
      status.removeAttribute("data-error");
      status.textContent = "";
      markError(0);
      repaint();
      input.scrollTop = 0;
      follow();
    } catch {
      swap(
        card,
        errorState(`${t("could-not-load-config-yml-for")}${name}.`, () => {
          swap(card, parts);
          load();
        })
      );
    } finally {
      revert.disabled = false;
      sync();
    }
  }

  load();

  return {
    el,
    destroy() {
      window.removeEventListener("beforeunload", beforeUnload);
    },
  };
}

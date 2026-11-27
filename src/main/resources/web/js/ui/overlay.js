// What floats over a page for a moment: toasts, dialogs that ask, and panes that show.
//
// A dialog is a native <dialog> opened with showModal(), so the page behind is inert, focus stays
// inside and Escape closes it without a line of code here; on close the focus goes back to what
// opened it. A destructive confirmation starts on Cancel, so a stray Enter undoes nothing. The
// inputs of a form are built in ui/fields.js, one kind at a time.

import { h, icon, swap } from "../lib/dom.js";
import { attachLens } from "../lib/glass.js";
import { t } from "../lib/i18n.js";
import { control } from "./fields.js";

// ---------------------------------------------------------------------------
// Toasts
// ---------------------------------------------------------------------------

let shelf = null;

function toastShelf() {
  if (shelf && shelf.isConnected) return shelf;
  shelf = h("div", { class: "toasts", role: "region", "aria-label": t("notifications") });
  document.body.append(shelf);
  return shelf;
}

const TOAST_ICON = { success: "circle-check", error: "circle-alert", info: "info" };
const TOAST_TONE = { success: "success", error: "danger", info: "accent" };

/**
 * Shows a short message at the bottom of the screen. Errors stay longer and are announced at once;
 * the rest politely. Hovering or focusing a toast holds it.
 */
export function toast(message, kind = "success") {
  const box = toastShelf();
  const node = h(
    "div",
    {
      class: "toast glass",
      role: kind === "error" ? "alert" : "status",
      data: { tone: TOAST_TONE[kind] || "accent" },
    },
    icon(TOAST_ICON[kind] || "info"),
    h("p", { text: message })
  );
  box.append(node);
  const detach = attachLens(node);

  // More than three at once is a wall: the oldest goes.
  const all = box.querySelectorAll(".toast:not([data-leaving])");
  if (all.length > 3) leave(all[0]);

  let remaining = kind === "error" ? 6000 : 3600;
  let started = performance.now();
  let timer = setTimeout(() => leave(node), remaining);
  const hold = () => {
    clearTimeout(timer);
    remaining -= performance.now() - started;
  };
  const resume = () => {
    started = performance.now();
    timer = setTimeout(() => leave(node), Math.max(1200, remaining));
  };
  node.addEventListener("pointerenter", hold);
  node.addEventListener("pointerleave", resume);

  function leave(target) {
    if (target.hasAttribute("data-leaving")) return;
    target.setAttribute("data-leaving", "");
    const gone = () => {
      if (target === node) detach();
      target.remove();
    };
    target.addEventListener("animationend", gone, { once: true });
    // Without animations (reduced motion), animationend never comes.
    setTimeout(gone, 260);
  }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

const newId = () => `dlg-${Math.random().toString(36).slice(2, 8)}`;

/**
 * Puts a <dialog> on screen, modal, and takes it away once closed: the focus goes back to what
 * opened it and `closed` resolves to what close() was given, null when it was dismissed.
 *
 * @param {HTMLDialogElement} dialog
 * @param {() => boolean} [busy] While it says so — a request is on its way — nothing closes the dialog.
 */
function present(dialog, busy = () => false) {
  const opener = document.activeElement;
  let result = null;
  let settle;
  const closed = new Promise((resolve) => {
    settle = resolve;
  });

  const close = (value) => {
    if (busy()) return;
    result = value;
    dialog.close();
  };

  // A click on the dimmed page around the pane dismisses it, like Escape — a click that began
  // there: text selected in a field with the button let go outside is not one.
  let pressed = null;
  dialog.addEventListener("pointerdown", (event) => {
    pressed = event.target;
  });
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog && pressed === dialog) close(null);
  });
  dialog.addEventListener("cancel", (event) => {
    if (busy()) event.preventDefault();
  });
  dialog.addEventListener("close", () => {
    detach();
    dialog.remove();
    if (opener && opener.isConnected && typeof opener.focus === "function") opener.focus({ preventScroll: true });
    settle(result);
  });

  document.body.append(dialog);
  const detach = attachLens(dialog);
  dialog.showModal();
  return { close, closed };
}

/** The kinds of field short enough for two to share a row: a price and its currency, two bounds. */
const SHORT = new Set(["number", "currency", "select"]);

/** Which fields share their row: two short ones that follow each other, taken two by two. */
function pairs(fields) {
  const half = new Set();
  for (let index = 0; index + 1 < fields.length; index++) {
    if (!SHORT.has(fields[index].type) || !SHORT.has(fields[index + 1].type)) continue;
    half.add(index).add(index + 1);
    index++;
  }
  return half;
}

/**
 * Opens a dialog and resolves once it is closed.
 *
 * options:
 *   title, text          what is asked
 *   fields               optional inputs: [{ key, label, type, placeholder, required, help,
 *                        value, min, max, step, options, optionsFrom }] — see ui/fields.js for
 *                        the types; a field with none is a text box
 *   confirmLabel         the button that does it
 *   tone                 "danger" pours the confirm button in the danger colour
 *   sendEmpty            true hands every field to onConfirm, an empty one as "": for a form
 *                        that edits, where "cleared" is not "not asked". Without it an empty
 *                        field is left out, as a form that creates always did
 *   onConfirm(values)    may return a promise; the dialog waits on it, shows its error and stays
 *                        open on failure, closes on success
 *
 * Resolves to the values on success, or null when dismissed.
 */
export function openDialog({ title, text, fields = [], confirmLabel, tone = "", sendEmpty = false, onConfirm }) {
  let busy = false;
  const titleId = newId();
  const error = h("p", { class: "form-error", role: "alert", hidden: true });

  const controls = fields.map((field) => control(field, `${titleId}-${field.key}`));
  const half = pairs(fields);
  const fieldNodes = fields.map((field, index) => {
    const made = controls[index];
    const id = `${titleId}-${field.key}`;
    const help = field.help ? h("p", { class: "field-help", id: `${id}-help`, text: field.help }) : null;
    if (help) made.target.setAttribute("aria-describedby", help.id);
    return h(
      "div",
      { class: `field${made.beside ? " field-beside" : ""}`, data: { half: half.has(index) || null } },
      h("label", { class: "field-label", for: id }, field.label, field.required ? h("span", { class: "req", "aria-hidden": "true", text: "*" }) : null),
      made.el,
      help
    );
  });

  const cancel = h("button", { class: "btn btn-quiet", type: "button", text: t("cancel"), onclick: () => pane.close(null) });
  const confirm = h(
    "button",
    { class: `btn glass-brass${tone === "danger" ? " is-danger" : ""}`, type: "submit" },
    h("span", { text: confirmLabel || t("confirm") })
  );

  const form = h(
    "form",
    { class: "dialog-body", method: "dialog", novalidate: true },
    h("h2", { class: "dialog-title", id: titleId, text: title || t("confirm-action") }),
    text ? h("p", { class: "dialog-text", text }) : null,
    fieldNodes.length ? h("div", { class: "dialog-fields" }, fieldNodes) : null,
    error,
    h("div", { class: "dialog-actions" }, cancel, confirm)
  );

  const dialog = h("dialog", { class: "dialog glass glass-deep", "aria-labelledby": titleId }, form);

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy) return;
    const values = {};
    let wrong = null;
    let complaint = "";
    fields.forEach((field, index) => {
      const made = controls[index];
      const empty = made.blank();
      // A required field left empty, or a value the field itself refuses: a number out of its bounds.
      const problem = empty ? (field.required ? t("please-fill-in-all-required-fields") : null) : made.check ? made.check() : null;
      if (problem) {
        made.target.setAttribute("aria-invalid", "true");
        if (!wrong) {
          wrong = made;
          complaint = problem;
        }
      } else made.target.removeAttribute("aria-invalid");
      if (!empty) values[field.key] = made.read();
      else if (sendEmpty) values[field.key] = "";
    });
    if (wrong) {
      showError(complaint);
      wrong.target.focus();
      return;
    }
    if (!onConfirm) return pane.close(values);
    busy = true;
    confirm.disabled = true;
    cancel.disabled = true;
    swap(confirm, h("span", { class: "spin", "aria-hidden": "true" }), h("span", { text: confirmLabel || t("confirm") }));
    try {
      await onConfirm(values);
      busy = false;
      pane.close(values);
    } catch (failure) {
      busy = false;
      confirm.disabled = false;
      cancel.disabled = false;
      swap(confirm, h("span", { text: confirmLabel || t("confirm") }));
      showError((failure && failure.message) || t("action-failed"));
    }
  });

  function showError(message) {
    swap(error, icon("circle-alert"), h("span", { text: message }));
    error.hidden = false;
  }

  const pane = present(dialog, () => busy);
  pane.closed.then(() => controls.forEach((made) => made.destroy && made.destroy()));
  const first = controls.length ? controls[0].target : tone === "danger" ? cancel : confirm;
  first.focus();
  return pane.closed;
}

/** Asks before doing something. Resolves true when confirmed. */
export async function confirmAction({ title, text, confirmLabel, tone = "danger", onConfirm }) {
  const values = await openDialog({ title, text, confirmLabel, tone, onConfirm });
  return values !== null;
}

/**
 * Opens a pane that shows rather than asks — a chart, what a cell of a grid holds — with a way
 * out at its corner. Resolves once it is closed: to what close() was given, or null.
 *
 * options:
 *   title          its heading
 *   lead, sub      what stands before the heading (an icon) and under it (a line of text)
 *   wide           true for a pane with something to draw
 *   mark           the colour of the module it belongs to, for what it draws
 *   build(close)   returns what goes under the heading; an element marked autofocus takes the focus,
 *                  and the pane stays scrolled to its heading — on a phone the buttons at the
 *                  bottom of a long pane would otherwise open it scrolled past what it shows
 */
export function openPane({ title, lead = null, sub = "", wide = false, mark = null, build }) {
  const titleId = newId();
  const dismiss = h(
    "button",
    { class: "btn btn-sm btn-icon btn-quiet pane-close", type: "button", "aria-label": t("close"), title: t("close"), onclick: () => pane.close(null) },
    icon("x")
  );
  const body = h(
    "div",
    { class: "dialog-body pane" },
    h(
      "div",
      { class: "pane-head" },
      lead,
      h("div", { class: "pane-heading" }, h("h2", { class: "dialog-title", id: titleId, text: title }), sub ? h("p", { class: "pane-sub", text: sub }) : null),
      dismiss
    ),
    build((value) => pane.close(value))
  );
  const dialog = h("dialog", { class: `dialog glass glass-deep${wide ? " dialog-wide" : ""}`, "aria-labelledby": titleId, vars: { mark } }, body);
  // Focused by hand rather than by the browser, which would scroll it into view.
  const marked = body.querySelector("[autofocus]");
  if (marked) marked.removeAttribute("autofocus");
  const pane = present(dialog);
  if (marked) marked.focus({ preventScroll: true });
  return pane.closed;
}

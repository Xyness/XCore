// What floats over a page for a moment: toasts and dialogs.
//
// A dialog is a native <dialog> opened with showModal(), so the page behind is inert, focus stays
// inside and Escape closes it without a line of code here; on close the focus goes back to what
// opened it. A destructive confirmation starts on Cancel, so a stray Enter undoes nothing.

import { h, icon, swap } from "../lib/dom.js";
import { attachLens } from "../lib/glass.js";
import { t } from "../lib/i18n.js";

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

/**
 * Opens a dialog and resolves once it is closed.
 *
 * options:
 *   title, text          what is asked
 *   fields               optional inputs: [{ key, label, type, placeholder, required }]
 *   confirmLabel         the button that does it
 *   tone                 "danger" pours the confirm button in the danger colour
 *   onConfirm(values)    may return a promise; the dialog waits on it, shows its error and stays
 *                        open on failure, closes on success
 *
 * Resolves to the values on success, or null when dismissed.
 */
export function openDialog({ title, text, fields = [], confirmLabel, tone = "", onConfirm }) {
  return new Promise((resolve) => {
    const opener = document.activeElement;
    const inputs = {};
    let busy = false;
    let result = null;

    const titleId = `dlg-${Math.random().toString(36).slice(2, 8)}`;
    const error = h("p", { class: "form-error", role: "alert", hidden: true });

    const fieldNodes = fields.map((field) => {
      const id = `${titleId}-${field.key}`;
      const input = h("input", {
        class: "input",
        id,
        name: field.key,
        type: field.type || "text",
        placeholder: field.placeholder || "",
        required: field.required || null,
        autocomplete: "off",
        spellcheck: false,
      });
      inputs[field.key] = input;
      return h(
        "div",
        { class: "field" },
        h("label", { class: "field-label", for: id }, field.label, field.required ? h("span", { class: "req", "aria-hidden": "true", text: "*" }) : null),
        input
      );
    });

    const cancel = h("button", { class: "btn btn-quiet", type: "button", text: t("cancel"), onclick: () => close(null) });
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

    const close = (value) => {
      if (busy) return;
      result = value;
      dialog.close();
    };

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (busy) return;
      const values = {};
      let missing = null;
      for (const field of fields) {
        const value = inputs[field.key].value.trim();
        inputs[field.key].removeAttribute("aria-invalid");
        if (field.required && !value) {
          inputs[field.key].setAttribute("aria-invalid", "true");
          missing = missing || inputs[field.key];
        }
        if (value) values[field.key] = value;
      }
      if (missing) {
        showError(t("please-fill-in-all-required-fields"));
        missing.focus();
        return;
      }
      if (!onConfirm) return close(values);
      busy = true;
      confirm.disabled = true;
      cancel.disabled = true;
      swap(confirm, h("span", { class: "spin", "aria-hidden": "true" }), h("span", { text: confirmLabel || t("confirm") }));
      try {
        await onConfirm(values);
        busy = false;
        close(values);
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

    // A click on the dimmed page around the pane dismisses it, like Escape.
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) close(null);
    });
    dialog.addEventListener("cancel", (event) => {
      if (busy) event.preventDefault();
    });
    dialog.addEventListener("close", () => {
      detach();
      dialog.remove();
      if (opener && opener.isConnected && typeof opener.focus === "function") opener.focus({ preventScroll: true });
      resolve(result);
    });

    document.body.append(dialog);
    const detach = attachLens(dialog);
    dialog.showModal();
    const first = fieldNodes.length ? inputs[fields[0].key] : tone === "danger" ? cancel : confirm;
    first.focus();
  });
}

/** Asks before doing something. Resolves true when confirmed. */
export async function confirmAction({ title, text, confirmLabel, tone = "danger", onConfirm }) {
  const values = await openDialog({ title, text, confirmLabel, tone, onConfirm });
  return values !== null;
}

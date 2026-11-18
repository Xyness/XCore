// The way in.
//
// There is no password: /xcore dashboard, in game, hands the player a link that carries a token
// and opens the dashboard logged in (index.html takes it out of the address bar). This screen is
// for the times the link was not clicked — paste the token, or the whole link, and connect.

import { h, icon, brandMark, swap } from "../lib/dom.js";
import { attachLens } from "../lib/glass.js";
import { get, token, ApiError } from "../lib/api.js";
import { t } from "../lib/i18n.js";
import { themeButton } from "../ui/shell.js";

/** The hint names the command; it reads better set as code wherever the translation keeps it. */
function hint() {
  const text = t("login-hint");
  const command = "/xcore dashboard";
  const at = text.indexOf(command);
  if (at < 0) return h("p", { class: "login-hint", text });
  return h("p", { class: "login-hint" }, text.slice(0, at), h("code", { text: command }), text.slice(at + command.length));
}

export function renderLogin(root, { message = "", onSuccess }) {
  const error = h("p", { class: "form-error", role: "alert", hidden: true });
  const input = h("input", {
    class: "input",
    id: "token",
    name: "token",
    type: "text",
    autocomplete: "off",
    autocapitalize: "off",
    spellcheck: false,
    placeholder: t("paste-your-token-here"),
  });
  const submit = h("button", { class: "btn glass-brass", type: "submit" }, h("span", { text: t("connect") }), icon("arrow-right"));

  const showError = (text) => {
    swap(error, icon("circle-alert"), h("span", { text }));
    error.hidden = false;
    input.setAttribute("aria-invalid", "true");
  };

  const form = h(
    "form",
    { novalidate: true },
    h("div", { class: "field" }, h("label", { class: "field-label", for: "token", text: t("api-token") }), input),
    error,
    submit
  );

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    let value = input.value.trim();
    // The whole link pasted rather than its token: take the token out of it.
    const fromLink = /[?&#]token=([^&#\s]+)/.exec(value);
    if (fromLink) value = decodeURIComponent(fromLink[1]);
    if (!value) {
      showError(t("please-enter-a-token"));
      input.focus();
      return;
    }
    submit.disabled = true;
    swap(submit, h("span", { class: "spin", "aria-hidden": "true" }), h("span", { text: t("connect") }));
    token.set(value);
    try {
      await get("/api/auth");
      cleanup();
      onSuccess();
    } catch (failure) {
      token.clear();
      submit.disabled = false;
      swap(submit, h("span", { text: t("connect") }), icon("arrow-right"));
      showError(
        failure instanceof ApiError && failure.status === 429 ? failure.message || t("invalid-token-or-server-unreachable") : t("invalid-token-or-server-unreachable")
      );
      input.select();
    }
  });
  input.addEventListener("input", () => {
    input.removeAttribute("aria-invalid");
    error.hidden = true;
  });

  const card = h(
    "div",
    { class: "login-card glass glass-deep enter" },
    h("span", { class: "login-mark" }, brandMark()),
    h("h1", { class: "login-title", text: t("xcore-dashboard") }),
    hint(),
    form
  );

  swap(
    root,
    h(
      "div",
      { class: "login" },
      h("div", { class: "login-corner" }, themeButton()),
      card
    )
  );

  const lenses = [attachLens(card), ...[...root.querySelectorAll(".login-corner .glass")].map((node) => attachLens(node))];
  const cleanup = () => lenses.forEach((undo) => undo());

  if (message) showError(message);
  input.focus();
}

// Talking to the panel.
//
// Every route is XCore's or a module's, unchanged: the token goes in an Authorization header, an
// answer is JSON. Two things are handled here once rather than on every page. A route nobody
// registered is answered by the panel's catch-all with index.html, so an answer that is not JSON
// counts as missing. And a 401 on any call means the session ran out while the page was open:
// the dashboard goes back to the login screen and says so.

const TOKEN_KEY = "xcore_token";

export const token = {
  get() {
    try {
      return localStorage.getItem(TOKEN_KEY) || "";
    } catch {
      return "";
    }
  },
  set(value) {
    try {
      localStorage.setItem(TOKEN_KEY, value);
    } catch {
      // Storage refused (a private window): the session lasts as long as the page.
    }
    memory = value;
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      // Nothing stored to remove.
    }
    memory = "";
  },
};

// The token index.html read, for a browser that refuses storage: the session then lasts as long
// as the page.
let memory = (window.__xcore && window.__xcore.token) || "";
const current = () => token.get() || memory;

export class ApiError extends Error {
  /**
   * @param {number} status HTTP status, or 0 when the server could not be reached.
   * @param {string} message What the server said ({"error": "..."}), or the status.
   */
  constructor(status, message) {
    super(message || `HTTP ${status}`);
    this.status = status;
  }
}

let onUnauthorized = () => {};

/** What to do when a call comes back 401: set once by the boot code. */
export function whenUnauthorized(handler) {
  onUnauthorized = handler;
}

/**
 * Requests started by index.html before any module had loaded, so the first page does not wait
 * for the scripts and then for the server in turn. Each one is used once, by the first call for
 * the same path; anything later asks again.
 */
function prefetched(path) {
  const early = window.__xcore && window.__xcore.early;
  if (!early || !early[path]) return null;
  const response = early[path];
  delete early[path];
  return response;
}

function headers(json) {
  const out = {};
  const value = current();
  if (value) out.Authorization = `Bearer ${value}`;
  if (json) out["Content-Type"] = "application/json";
  return out;
}

async function read(response, path) {
  const type = response.headers.get("Content-Type") || "";
  const isJson = type.includes("json");
  let body = null;
  if (isJson) {
    try {
      body = await response.json();
    } catch {
      body = null;
    }
  }
  if (response.status === 401 && path !== "/api/auth") onUnauthorized();
  if (!response.ok) {
    // Some refusals explain themselves in a second field: "Password refused" says little
    // without the policy sentence that follows it.
    const said = body && typeof body === "object" ? [body.error, body.message].filter((part) => typeof part === "string" && part) : [];
    throw new ApiError(response.status, [...new Set(said)].join(" — "));
  }
  if (!isJson) throw new ApiError(404, "");
  return body;
}

/** GET a JSON route. Throws ApiError on any failure. */
export async function get(path, { signal } = {}) {
  let response;
  try {
    response = (await prefetched(path)) || (await fetch(path, { headers: headers(false), signal }));
  } catch (error) {
    if (error && error.name === "AbortError") throw error;
    throw new ApiError(0, "");
  }
  return read(response, path);
}

/** POST a JSON body. Throws ApiError carrying the server's own message when it gives one. */
export async function post(path, body) {
  let response;
  try {
    response = await fetch(path, { method: "POST", headers: headers(true), body: JSON.stringify(body || {}) });
  } catch {
    throw new ApiError(0, "");
  }
  return read(response, path);
}

/** A path with a query string built from the defined, non-empty values of `params`. */
export function withQuery(path, params) {
  const parts = [];
  for (const key in params) {
    const value = params[key];
    if (value === null || value === undefined || value === "") continue;
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  }
  if (parts.length === 0) return path;
  return path + (path.includes("?") ? "&" : "?") + parts.join("&");
}

/** The base of a module's routes, as the panel registers them: /api/<name in lower case>. */
export const moduleBase = (name) => `/api/${String(name).toLowerCase()}`;

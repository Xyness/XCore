// The server's live figures (/api/metrics), read once for everyone who shows them.
//
// The sidebar's live dot and the home page both want them; asking twice would double the load on
// a panel that rate-limits by address. One poll, at the pace of the most eager reader: every five
// seconds while the home page is open — the figures that move are players, TPS and memory — and
// every thirty otherwise. A tab nobody is looking at is not polled at all, and catches up the
// moment it is shown again.

import { get } from "./api.js";

const SLOW = 30_000;

let last = null;
let timer = 0;
let pace = SLOW;
let inflight = null;
const readers = new Set();
const paces = new Map();

export const latest = () => last;

/** Calls `fn` with every new reading, at once with the last one if there is one. */
export function onMetrics(fn) {
  readers.add(fn);
  if (last) fn(last);
  return () => readers.delete(fn);
}

export function refresh() {
  if (inflight) return inflight;
  inflight = get("/api/metrics")
    .then((data) => {
      last = data && typeof data === "object" ? data : {};
      for (const fn of readers) {
        try {
          fn(last);
        } catch (error) {
          console.error(error);
        }
      }
      return last;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

function schedule() {
  clearTimeout(timer);
  if (document.hidden) return;
  timer = setTimeout(() => {
    refresh().catch(() => {}).finally(schedule);
  }, pace);
}

/**
 * Asks for readings every `ms` until the returned function is called. The fastest request wins.
 */
export function wantEvery(ms) {
  const key = Symbol("pace");
  paces.set(key, ms);
  repace();
  return () => {
    paces.delete(key);
    repace();
  };
}

function repace() {
  const next = Math.min(SLOW, ...paces.values());
  if (next !== pace) {
    pace = next;
    schedule();
  }
}

export function startMetrics() {
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) clearTimeout(timer);
    else refresh().catch(() => {}).finally(schedule);
  });
  return refresh()
    .catch(() => null)
    .finally(schedule);
}

/** How a TPS reads: "ok" from 18, "slow" from 15, "bad" under — the old dashboard's bounds. */
export function tpsState(tps) {
  if (typeof tps !== "number" || tps <= 0) return null;
  if (tps < 15) return "bad";
  if (tps < 18) return "slow";
  return "ok";
}

/** Memory pressure, the same way: past 65% is worth a look, past 85% a problem. */
export function memoryState(used, max) {
  if (typeof used !== "number" || typeof max !== "number" || max <= 0) return null;
  const share = used / max;
  if (share > 0.85) return "bad";
  if (share > 0.65) return "slow";
  return "ok";
}

export const STATE_TONE = { ok: "success", slow: "warning", bad: "danger" };

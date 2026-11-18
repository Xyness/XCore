// Charts and heat maps, loaded only by the pages that have them.
//
// A module declares a series (WebPageSpec#chart) by naming its label and value fields; how it is
// drawn follows what the series is. A long run of moments — a TPS sample every ten seconds — is a
// trend, so it is a line over a faint wash with a crosshair that finds the nearest point. Anything
// else — sales per day, a handful of categories — is a column each. One series, one colour: the
// module's own. Gridlines are hairlines, the axis says only what the marks do not, and every value
// is one hover, one arrow key or one "Table" away.
//
// A heat map is a top-down grid, one cell per coordinate pair, laid out from the extents of the
// data itself so a sparse map stays readable. It is one hue, from the surface to the brass: more
// is brighter in the dark theme and darker on paper, with the scale under it.

import { h, icon, swap, setVar } from "../lib/dom.js";
import { t, num, fixed, clock, date, parseDate, fieldLabel, looksTemporal } from "../lib/i18n.js";
import { dataTable } from "../ui/table.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const HEIGHT = 220;
const PAD = { top: 12, right: 8, bottom: 26, left: 44 };

function svg(tag, attributes = {}) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const name in attributes) node.setAttribute(name, attributes[name]);
  return node;
}

/** Round tick values between `min` and `max`: 0, 5, 10, 15, 20 rather than 0, 4.93, 9.87… */
function niceTicks(max, min = 0, count = 4) {
  if (!(max > min)) return [min, min + 1];
  const raw = (max - min) / count;
  const power = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((f) => f * power).find((candidate) => candidate >= raw) || 10 * power;
  const start = Math.floor(min / step) * step;
  const ticks = [];
  for (let value = start; value <= max + step * 0.001; value += step) ticks.push(Number(value.toPrecision(12)));
  if (ticks[ticks.length - 1] < max) ticks.push(Number((ticks[ticks.length - 1] + step).toPrecision(12)));
  return ticks;
}

/** Compact on an axis that reaches ten thousand: 10K, 20K — in full below. */
function tickLabel(value, top) {
  if (top >= 10000) return num(value, { notation: "compact", maximumFractionDigits: 1 });
  return Number.isInteger(value) ? num(value) : fixed(value, 1);
}

const valueText = (value) => (typeof value !== "number" ? String(value ?? "—") : Number.isInteger(value) ? num(value) : fixed(value, 2));

/**
 * The label of a point. A moment reads as the hour for a series within a day and as the date
 * beyond; a long string — "2026-11-18" — is cut to what differs from its neighbours.
 */
function labeller(points) {
  const moments = points.map((point) => (typeof point.label === "number" || typeof point.label === "string" ? parseDate(point.label) : null));
  const temporal = moments.length > 0 && moments.every(Boolean);
  if (temporal) {
    const span = Math.abs(moments[moments.length - 1] - moments[0]);
    return { temporal, text: (index) => (span < 36 * 3600 * 1000 ? clock(moments[index]) : date(moments[index])), full: (index) => `${date(moments[index])} ${clock(moments[index])}` };
  }
  return { temporal, text: (index) => String(points[index].label ?? ""), full: (index) => String(points[index].label ?? "") };
}

/**
 * A chart of one series.
 *
 * @param {object[]} rows  The series, as the module sent it.
 * @param {object} chart   The page's chart descriptor (labelField, valueField, maxPoints, titleKey).
 */
export function barChart(rows, chart) {
  let series = rows;
  if (chart.maxPoints > 0 && series.length > chart.maxPoints) {
    // More marks than pixels helps nobody: keep an evenly spaced sample of them.
    const step = Math.ceil(series.length / chart.maxPoints);
    series = series.filter((_, index) => index % step === 0);
  }
  let points = series.map((row) => ({ label: row[chart.labelField], value: Number(row[chart.valueField]) || 0, row }));
  // Time reads left to right: a series sent newest first is turned round.
  const firstMoment = points.length > 1 ? parseDate(points[0].label) : null;
  const lastMoment = points.length > 1 ? parseDate(points[points.length - 1].label) : null;
  if (firstMoment && lastMoment && firstMoment > lastMoment) points = points.reverse();
  const labels = labeller(points);
  const asLine = labels.temporal && points.length > 30;

  const plot = h("div", { class: "chart-plot" });
  const tip = h("div", { class: "chart-tip", role: "presentation" }, h("strong", { class: "num" }), h("span"));
  const title = t(chart.titleKey);
  const values = points.map((point) => point.value);
  const max = Math.max(...values, 0);
  const min = Math.min(...values);
  const summary = t("chart-summary", {
    title,
    count: num(points.length),
    min: valueText(min),
    max: valueText(max),
    last: valueText(values[values.length - 1]),
  });
  const frame = h("div", { class: "chart", tabindex: "0", role: "img", "aria-label": summary }, plot, tip);

  const tableView = h("div", { class: "chart-table", hidden: true });
  const toggle = h(
    "button",
    { class: "btn btn-xs btn-quiet chart-toggle", type: "button", "aria-pressed": "false" },
    icon("list"),
    h("span", { text: t("show-table") })
  );
  toggle.addEventListener("click", () => {
    const showing = tableView.hidden;
    tableView.hidden = !showing;
    frame.hidden = showing;
    toggle.setAttribute("aria-pressed", String(showing));
    toggle.lastChild.textContent = t(showing ? "show-chart" : "show-table");
    if (showing && !tableView.firstChild) {
      tableView.append(
        dataTable(
          points.map((point, index) => ({ [chart.labelField]: labels.full(index), [chart.valueField]: point.value })),
          { caption: title }
        )
      );
    }
  });

  let width = 0;
  let active = -1;
  let geometry = null;

  function draw() {
    width = plot.clientWidth;
    if (width < 40) return;
    // Columns stand on zero, always. A line may start higher when its values never come near it —
    // a TPS between 17 and 20 drawn from zero is a flat line with its dips flattened out — and
    // the axis says where it starts.
    const floor = asLine && min > 0 && min > max * 0.5 ? min - (max - min) * 0.25 : 0;
    const ticks = niceTicks(max, floor);
    const bottom = ticks[0];
    const top = ticks[ticks.length - 1] || 1;
    const innerW = width - PAD.left - PAD.right;
    const innerH = HEIGHT - PAD.top - PAD.bottom;
    const y = (value) => PAD.top + innerH - ((value - bottom) / (top - bottom || 1)) * innerH;
    const root = svg("svg", { width, height: HEIGHT, viewBox: `0 0 ${width} ${HEIGHT}`, class: "chart-svg", "aria-hidden": "true" });

    // Gridlines and the y axis: hairlines, one step off the surface.
    for (const tick of ticks) {
      const ty = Math.round(y(tick)) + 0.5;
      root.append(svg("line", { x1: PAD.left, x2: width - PAD.right, y1: ty, y2: ty, class: tick === bottom ? "chart-base" : "chart-grid" }));
      const label = svg("text", { x: PAD.left - 8, y: ty + 4, class: "chart-tick", "text-anchor": "end" });
      label.textContent = tickLabel(tick, top);
      root.append(label);
    }

    const n = points.length;
    const xs = [];
    if (asLine) {
      for (let index = 0; index < n; index++) xs.push(PAD.left + (n === 1 ? innerW / 2 : (index / (n - 1)) * innerW));
      const line = xs.map((x, index) => `${index ? "L" : "M"}${x.toFixed(1)} ${y(points[index].value).toFixed(1)}`).join(" ");
      root.append(svg("path", { d: `${line} L${xs[n - 1].toFixed(1)} ${y(bottom)} L${xs[0].toFixed(1)} ${y(bottom)} Z`, class: "chart-area" }));
      root.append(svg("path", { d: line, class: "chart-line" }));
      root.append(svg("line", { class: "chart-cross", x1: 0, x2: 0, y1: PAD.top, y2: y(bottom), visibility: "hidden" }));
      root.append(svg("circle", { class: "chart-dot", r: 4, cx: 0, cy: 0, visibility: "hidden" }));
    } else {
      // Columns: at most 24px thick, a 2px gap of surface between neighbours, rounded at the
      // data end and square on the baseline.
      const band = innerW / n;
      const thick = Math.max(2, Math.min(24, band - 2));
      for (let index = 0; index < n; index++) {
        const cx = PAD.left + band * index + band / 2;
        xs.push(cx);
        const value = points[index].value;
        const height = Math.max(value > 0 ? 2 : 0, y(0) - y(value));
        const x0 = cx - thick / 2;
        const y0 = y(0) - height;
        const r = Math.min(4, thick / 2, height);
        const d = `M${x0} ${y(0)} V${y0 + r} Q${x0} ${y0} ${x0 + r} ${y0} H${x0 + thick - r} Q${x0 + thick} ${y0} ${x0 + thick} ${y0 + r} V${y(0)} Z`;
        root.append(svg("path", { d, class: "chart-bar", "data-index": index }));
      }
    }

    // The x axis says where the series starts, where it ends, and a few places between — never
    // two labels so close they run into each other.
    const every = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(innerW / 90))));
    for (let index = 0; index < n; index += every) {
      if (index > 0 && xs[n - 1] - xs[index] < 110) continue;
      const label = svg("text", { x: xs[index], y: HEIGHT - 8, class: "chart-tick", "text-anchor": index === 0 && asLine ? "start" : "middle" });
      label.textContent = labels.text(index);
      root.append(label);
    }
    if (n > 1) {
      const label = svg("text", { x: xs[n - 1], y: HEIGHT - 8, class: "chart-tick", "text-anchor": asLine ? "end" : "middle" });
      label.textContent = labels.text(n - 1);
      root.append(label);
    }

    geometry = { xs, y, root };
    swap(plot, root);
    if (active >= 0) show(active);
  }

  function show(index) {
    if (!geometry || index < 0 || index >= points.length) return;
    active = index;
    const { xs, y, root } = geometry;
    const x = xs[index];
    const value = points[index].value;
    if (asLine) {
      const cross = root.querySelector(".chart-cross");
      const dot = root.querySelector(".chart-dot");
      cross.setAttribute("x1", x);
      cross.setAttribute("x2", x);
      cross.setAttribute("visibility", "visible");
      dot.setAttribute("cx", x);
      dot.setAttribute("cy", y(value));
      dot.setAttribute("visibility", "visible");
    } else {
      for (const bar of root.querySelectorAll(".chart-bar[data-on]")) bar.removeAttribute("data-on");
      const bar = root.querySelector(`.chart-bar[data-index="${index}"]`);
      if (bar) bar.setAttribute("data-on", "");
    }
    tip.firstChild.textContent = valueText(value);
    tip.lastChild.textContent = labels.full(index);
    frame.setAttribute("data-tip", "");
    const tipWidth = tip.offsetWidth;
    const left = Math.min(Math.max(0, x - tipWidth / 2), width - tipWidth);
    // Above the point when there is room, below it when the point is near the top: the readout
    // never sits on the value it reads.
    const above = y(value) - tip.offsetHeight - 12;
    const top = above >= 0 ? above : Math.min(y(value) + 14, HEIGHT - tip.offsetHeight);
    tip.style.transform = `translate3d(${Math.round(left)}px, ${Math.round(top)}px, 0)`;
  }

  function hide() {
    active = -1;
    frame.removeAttribute("data-tip");
    if (!geometry) return;
    for (const node of geometry.root.querySelectorAll(".chart-cross, .chart-dot")) node.setAttribute("visibility", "hidden");
    for (const bar of geometry.root.querySelectorAll(".chart-bar[data-on]")) bar.removeAttribute("data-on");
  }

  /** The point nearest the pointer, along x: nobody can aim at a 2px line. */
  function nearest(clientX) {
    if (!geometry) return -1;
    const x = clientX - plot.getBoundingClientRect().left;
    let best = 0;
    for (let index = 1; index < geometry.xs.length; index++) {
      if (Math.abs(geometry.xs[index] - x) < Math.abs(geometry.xs[best] - x)) best = index;
    }
    return best;
  }

  frame.addEventListener("pointermove", (event) => show(nearest(event.clientX)));
  frame.addEventListener("pointerleave", hide);
  frame.addEventListener("blur", hide);
  frame.addEventListener("focus", () => show(active >= 0 ? active : points.length - 1));
  frame.addEventListener("keydown", (event) => {
    const moves = { ArrowLeft: -1, ArrowRight: 1, Home: -Infinity, End: Infinity };
    if (!(event.key in moves)) return;
    event.preventDefault();
    const from = active >= 0 ? active : points.length - 1;
    const step = moves[event.key];
    const next = step === -Infinity ? 0 : step === Infinity ? points.length - 1 : Math.min(points.length - 1, Math.max(0, from + step));
    show(next);
  });

  const resizer = new ResizeObserver(() => {
    if (plot.clientWidth !== width) draw();
  });
  resizer.observe(plot);

  return h("div", { class: "chart-holder" }, h("div", { class: "chart-tools" }, toggle), frame, tableView);
}

/**
 * A top-down grid of values: where the load is, rather than how much of it there is. Rows from
 * several worlds are drawn one world at a time, with a switch between them — one grid of all of
 * them would put two unrelated places in the same cell.
 */
export function heatmap(rows, conf, choice = {}) {
  // Either the payload says which worlds there are and which one it describes — then another one
  // is asked of the server (choice.onWorld) — or each row names its world and they are split here.
  const external = Array.isArray(choice.worlds) && choice.worlds.length > 1;
  const worldKey = external ? null : ["world", "world_name"].find((key) => rows.some((row) => typeof row[key] === "string"));
  const worlds = external ? choice.worlds : worldKey ? [...new Set(rows.map((row) => row[worldKey]))] : [null];
  if (!external) {
    const totals = new Map(worlds.map((world) => [world, 0]));
    for (const row of rows) totals.set(worldKey ? row[worldKey] : null, (totals.get(worldKey ? row[worldKey] : null) || 0) + (Number(row[conf.valueField]) || 0));
    worlds.sort((a, b) => totals.get(b) - totals.get(a));
  }

  const holder = h("div", { class: "heat" });
  const stage = h("div", { class: "heat-stage" });
  const tip = h("div", { class: "chart-tip", role: "presentation" }, h("strong", { class: "num" }), h("span"));
  const legend = h("div", { class: "heat-legend" });
  const tableView = h("div", { class: "chart-table", hidden: true });

  let current = external && choice.world && worlds.includes(choice.world) ? choice.world : worlds[0];

  const switcher = worlds.length > 1
    ? h(
        "div",
        { class: "segmented", role: "group", "aria-label": fieldLabel(worldKey || "world") },
        worlds.map((world) =>
          h("button", { class: "segment", type: "button", "aria-pressed": String(world === current), text: world, onclick: () => pick(world) })
        )
      )
    : null;

  const toggle = h("button", { class: "btn btn-xs btn-quiet chart-toggle", type: "button", "aria-pressed": "false" }, icon("list"), h("span", { text: t("show-table") }));

  function pick(world) {
    if (world === current) return;
    current = world;
    if (switcher) for (const button of switcher.children) button.setAttribute("aria-pressed", String(button.textContent === world));
    if (external) {
      holder.setAttribute("data-busy", "");
      choice.onWorld(world);
      return;
    }
    render();
  }

  function render() {
    const subset = worldKey ? rows.filter((row) => row[worldKey] === current) : rows;
    if (subset.length === 0) {
      swap(stage, h("p", { class: "state", text: t(conf.emptyKey || "no-chart-data-available") }));
      legend.replaceChildren();
      return;
    }
    let minX = Infinity;
    let maxX = -Infinity;
    let minZ = Infinity;
    let maxZ = -Infinity;
    let peak = 0;
    for (const row of subset) {
      const x = Number(row[conf.xField]);
      const z = Number(row[conf.zField]);
      const value = Number(row[conf.valueField]) || 0;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (z < minZ) minZ = z;
      if (z > maxZ) maxZ = z;
      if (value > peak) peak = value;
    }
    const columns = Math.max(1, maxX - minX + 1);
    const lines = Math.max(1, maxZ - minZ + 1);
    const cells = new Map(subset.map((row) => [`${Number(row[conf.xField])}:${Number(row[conf.zField])}`, row]));

    // A grid that would be thousands of cells is not a map any more: past that the table says it
    // better, and the browser is spared.
    if (columns * lines > 6000) {
      swap(stage, h("p", { class: "state", text: t("heatmap-too-large") }));
      tableView.hidden = false;
      fillTable(subset);
      return;
    }

    const grid = h("div", { class: "heat-grid", vars: { cols: columns, rows: lines }, tabindex: "0", role: "img", "aria-label": t("heatmap-summary", { count: num(subset.length), peak: valueText(peak) }) });
    const fragment = document.createDocumentFragment();
    for (let line = 0; line < lines; line++) {
      for (let column = 0; column < columns; column++) {
        const row = cells.get(`${minX + column}:${minZ + line}`);
        if (!row) {
          fragment.append(h("i", { data: { empty: true } }));
          continue;
        }
        const value = Number(row[conf.valueField]) || 0;
        // On a square-root scale: load is a long tail, and a linear one leaves every cell but the
        // few hottest the colour of an empty one.
        const cell = h("i", { vars: { v: peak > 0 ? Math.sqrt(value / peak).toFixed(3) : 0 } });
        cell.dataset.x = String(row[conf.xField]);
        cell.dataset.z = String(row[conf.zField]);
        cell.dataset.value = String(value);
        fragment.append(cell);
      }
    }
    grid.append(fragment);

    let lit = null;
    const showCell = (cell) => {
      if (!cell || cell.dataset.empty !== undefined || cell.dataset.x === undefined) return hideCell();
      if (lit) lit.removeAttribute("data-on");
      lit = cell;
      cell.setAttribute("data-on", "");
      tip.firstChild.textContent = valueText(Number(cell.dataset.value));
      tip.lastChild.textContent = `${fieldLabel(conf.xField)} ${cell.dataset.x} · ${fieldLabel(conf.zField)} ${cell.dataset.z}`;
      holder.setAttribute("data-tip", "");
      const box = cell.getBoundingClientRect();
      const frame = stage.getBoundingClientRect();
      const left = Math.min(Math.max(0, box.left - frame.left + box.width / 2 - tip.offsetWidth / 2), frame.width - tip.offsetWidth);
      const top = Math.max(0, box.top - frame.top - tip.offsetHeight - 8);
      tip.style.transform = `translate3d(${Math.round(left)}px, ${Math.round(top)}px, 0)`;
    };
    const hideCell = () => {
      if (lit) lit.removeAttribute("data-on");
      lit = null;
      holder.removeAttribute("data-tip");
    };
    grid.addEventListener("pointermove", (event) => showCell(event.target.closest("i")));
    grid.addEventListener("pointerleave", hideCell);
    grid.addEventListener("blur", hideCell);
    // The arrow keys walk the hottest cells, hottest first: on a map of a few hundred chunks the
    // ones worth reaching are the ones that matter.
    const hottest = [...grid.querySelectorAll("i[data-value]")].sort((a, b) => Number(b.dataset.value) - Number(a.dataset.value));
    let cursor = -1;
    grid.addEventListener("keydown", (event) => {
      if (event.key !== "ArrowRight" && event.key !== "ArrowLeft" && event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
      event.preventDefault();
      cursor = Math.min(hottest.length - 1, Math.max(0, cursor + (event.key === "ArrowRight" || event.key === "ArrowDown" ? 1 : -1)));
      showCell(hottest[cursor]);
    });
    grid.addEventListener("focus", () => {
      if (cursor < 0) cursor = 0;
      showCell(hottest[cursor]);
    });

    swap(stage, grid, tip);
    swap(
      legend,
      h("span", { class: "label", text: "0" }),
      h("span", { class: "heat-ramp", "aria-hidden": "true" }),
      h("span", { class: "label num", text: valueText(peak) }),
      h("span", { class: "heat-legend-name", text: fieldLabel(conf.valueField) })
    );
    if (!tableView.hidden) fillTable(subset);
    else tableView.replaceChildren();
  }

  function fillTable(subset) {
    const sorted = [...subset].sort((a, b) => (Number(b[conf.valueField]) || 0) - (Number(a[conf.valueField]) || 0));
    swap(tableView, dataTable(sorted, { caption: t(conf.titleKey) }));
  }

  toggle.addEventListener("click", () => {
    const showing = tableView.hidden;
    tableView.hidden = !showing;
    stage.hidden = showing;
    legend.hidden = showing;
    toggle.setAttribute("aria-pressed", String(showing));
    toggle.lastChild.textContent = t(showing ? "show-chart" : "show-table");
    if (showing) fillTable(worldKey ? rows.filter((row) => row[worldKey] === current) : rows);
  });

  holder.append(h("div", { class: "chart-tools" }, switcher, toggle), stage, legend, tableView);
  render();
  return holder;
}

export { looksTemporal, setVar };

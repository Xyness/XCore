// The lens of the glass (see "Glass" in css/theme.css) — the site's, from lib/liquid-glass.ts.
//
// A blurred backdrop on its own is frosted glass. What makes a piece read as thick and clear is
// its rim: light bends there, so the edge shows a squeezed copy of whatever lies a little further
// in, sharp, while the middle stays frosted. That is a displacement map and a blur that spares the
// rim, and an SVG filter can apply both to the backdrop.
//
// Only Chromium applies an SVG filter named in backdrop-filter. Elsewhere a declaration that names
// one is not applied at all, its blur included — which is why the lens is switched on from here,
// per element and only for Chromium, and never from the stylesheet. Without it the glass is
// exactly what the stylesheet says: plate, sheen, rim and an even blur.

const SVG_NS = "http://www.w3.org/2000/svg";

/** The refractive index of glass. */
const REFRACTION = 1.5;

/** How tall the rounded edge is for its width: the higher, the harder it bends. */
const RIM_RATIO = 1.35;

/** The flat glass under the rounded edge, as a share of the edge's height. */
const SLAB = 0.6;

/** The share of the rim, from the edge inwards, that is left perfectly clear. */
const CLEAR = 0.3;

const STEPS = 128;

/** Above this many pixels a lens costs more to draw than it gives: the piece keeps the blur. */
const MAX_AREA = 420_000;

const clamp = (value, min, max) => (value < min ? min : value > max ? max : value);

/** Eases in and out, with no speed at either end. */
const settle = (amount) => amount * amount * (3 - 2 * amount);

/**
 * How far sideways a ray lands, by depth into the rim: index 0 is the very edge, the last one
 * where the rim meets the flat of the glass. Normalised so the furthest throw is 1. The edge is
 * rounded like a squircle — steep at the outside, level on top — and each ray is bent by Snell's
 * law where it enters, then travels through whatever glass is under it.
 */
let rimThrow = null;

function rimProfile() {
  if (rimThrow) return rimThrow;
  const profile = new Float32Array(STEPS + 1);
  let peak = 0;
  for (let step = 0; step <= STEPS; step++) {
    const outward = 1 - step / STEPS;
    const body = Math.max(1e-6, 1 - outward ** 4);
    const height = body ** 0.25;
    const slope = outward ** 3 / body ** 0.75;
    const incidence = Math.atan(slope * RIM_RATIO);
    const refracted = Math.asin(Math.sin(incidence) / REFRACTION);
    profile[step] = Math.tan(incidence - refracted) * (height + SLAB);
    if (profile[step] > peak) peak = profile[step];
  }
  for (let step = 0; step <= STEPS; step++) profile[step] /= peak;
  rimThrow = profile;
  return profile;
}

/**
 * The lens of a rounded rectangle, as a PNG data URL. Red is how far to look sideways for each
 * pixel and green how far up or down, mid-grey being not at all; every pixel of the rim looks
 * inwards, so the filter never reaches for something outside the element. Blue is how frosted
 * the glass is there: nothing at the edge, fully by the time the rim ends.
 */
function drawLens(width, height, radius, rim) {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) return null;

  const profile = rimProfile();
  const image = context.createImageData(width, height);
  const pixels = image.data;
  const halfWidth = width / 2;
  const halfHeight = height / 2;

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      // From the centre of the shape, folded into one corner of it.
      const fromCentreX = x + 0.5 - halfWidth;
      const fromCentreY = y + 0.5 - halfHeight;
      const cornerX = Math.abs(fromCentreX) - (halfWidth - radius);
      const cornerY = Math.abs(fromCentreY) - (halfHeight - radius);
      const roundX = Math.max(cornerX, 0);
      const roundY = Math.max(cornerY, 0);
      const round = Math.hypot(roundX, roundY);
      const depth = radius - round - Math.min(Math.max(cornerX, cornerY), 0);

      // Which way is out: along the corner's radius, or straight to the nearest side.
      let outX = 0;
      let outY = 0;
      if (round > 0) {
        outX = (roundX / round) * Math.sign(fromCentreX);
        outY = (roundY / round) * Math.sign(fromCentreY);
      } else if (cornerX > cornerY) {
        outX = Math.sign(fromCentreX);
      } else {
        outY = Math.sign(fromCentreY);
      }

      let reach = 0;
      let frost = 1;
      if (depth <= 0) {
        reach = profile[0];
        frost = 0;
      } else if (depth < rim) {
        const position = (depth / rim) * STEPS;
        const index = Math.floor(position);
        reach = profile[index] + (profile[index + 1] - profile[index]) * (position - index);
        frost = settle(clamp((depth / rim - CLEAR) / (1 - CLEAR), 0, 1));
      }

      const offset = (y * width + x) * 4;
      pixels[offset] = Math.round(128 - outX * reach * 127);
      pixels[offset + 1] = Math.round(128 - outY * reach * 127);
      pixels[offset + 2] = Math.round(frost * 255);
      pixels[offset + 3] = 255;
    }
  }

  context.putImageData(image, 0, 0);
  return canvas.toDataURL("image/png");
}

let refracts = null;

/** Whether this browser applies an SVG filter to a backdrop: Chromium does. */
function canRefract() {
  if (refracts === null) {
    const brands = (navigator.userAgentData && navigator.userAgentData.brands) || [];
    refracts =
      brands.some((entry) => entry.brand === "Chromium") &&
      !window.matchMedia("(prefers-reduced-transparency: reduce), (prefers-contrast: more)").matches;
  }
  return refracts;
}

let holder = null;
let count = 0;

/** One hidden <svg> for every lens on the page. */
function lensHolder() {
  if (holder && holder.isConnected) return holder;
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("width", "0");
  svg.setAttribute("height", "0");
  svg.setAttribute("class", "lens-holder");
  holder = document.createElementNS(SVG_NS, "defs");
  svg.appendChild(holder);
  document.body.appendChild(svg);
  return holder;
}

/**
 * Gives a .glass element its lens, and keeps the lens the element's size. Returns what undoes it.
 * Does nothing where the browser cannot refract.
 */
export function attachLens(element) {
  if (!element || !canRefract()) return () => {};

  const id = `glass-lens-${++count}`;
  let filter = null;
  let map = null;
  let bend = null;
  let shape = "";
  let frame = 0;
  let alive = true;

  const clear = () => {
    element.style.removeProperty("--glass-lens");
    if (filter) filter.remove();
    filter = map = bend = null;
  };

  const make = (tag, attributes) => {
    const node = document.createElementNS(SVG_NS, tag);
    for (const name in attributes) node.setAttribute(name, attributes[name]);
    return node;
  };

  /** Builds the filter: bend the backdrop, then frost all of it but the rim. */
  const build = () => {
    // The stylesheet's own blur, so the frosted middle matches the glass of a browser that gets
    // no lens.
    const blur = parseFloat(getComputedStyle(element).getPropertyValue("--glass-blur").replace(/[^\d.]/g, ""));
    filter = make("filter", {
      id,
      x: "0",
      y: "0",
      width: "100%",
      height: "100%",
      // The map is numbers, not a picture: read it as written.
      "color-interpolation-filters": "sRGB",
    });
    map = make("feImage", { x: "0", y: "0", preserveAspectRatio: "none", result: "lens" });
    bend = make("feDisplacementMap", {
      in: "SourceGraphic",
      in2: "lens",
      xChannelSelector: "R",
      yChannelSelector: "G",
      result: "bent",
    });
    filter.append(
      map,
      bend,
      // A squeezed image shimmers; half a pixel of blur settles it.
      make("feGaussianBlur", { in: "bent", stdDeviation: "0.6", result: "rim" }),
      make("feGaussianBlur", { in: "bent", stdDeviation: String(Number.isFinite(blur) ? blur : 9), result: "frost" }),
      // The map's blue channel, as the opacity of the frosted layer.
      make("feColorMatrix", {
        in: "lens",
        type: "matrix",
        values: "0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 1 0 0",
        result: "plate",
      }),
      make("feComposite", { in: "frost", in2: "plate", operator: "in", result: "frosted" }),
      make("feComposite", { in: "frosted", in2: "rim", operator: "over" })
    );
    lensHolder().appendChild(filter);
  };

  const update = () => {
    frame = 0;
    const width = element.offsetWidth;
    const height = element.offsetHeight;
    const short = Math.min(width, height);
    const declared = getComputedStyle(element).borderTopLeftRadius;
    const radius = clamp(
      declared.endsWith("%") ? (parseFloat(declared) / 100) * short : parseFloat(declared) || 0,
      0,
      short / 2
    );

    const next = `${width}x${height}r${radius}`;
    if (next === shape) return;
    shape = next;

    // Not on screen at this size — a bar that only exists on a phone — or too big to be worth it.
    if (short < 8 || width * height > MAX_AREA) return clear();

    // The rim is a fifth of the short side, within reason: a button gets a tight edge, a panel a
    // broader one.
    const rim = clamp(short * 0.2, 6, 16);
    const url = drawLens(width, height, radius, rim);
    if (!url) return clear();

    if (!filter) build();

    map.setAttribute("href", url);
    map.setAttribute("width", String(width));
    map.setAttribute("height", String(height));
    // The scale is the full swing of a channel, so twice the furthest throw: the very edge shows
    // what lies one and a half rims further in.
    bend.setAttribute("scale", (rim * 3).toFixed(1));

    // Point the element at the filter once the map has been decoded, so there is never a frame of
    // a backdrop with nothing in it.
    const probe = new Image();
    probe.src = url;
    const apply = () => {
      if (alive && shape === next) element.style.setProperty("--glass-lens", `url(#${id})`);
    };
    probe.decode().then(apply, apply);
  };

  const observer = new ResizeObserver(() => {
    if (!frame) frame = requestAnimationFrame(update);
  });
  observer.observe(element);
  update();

  return () => {
    alive = false;
    cancelAnimationFrame(frame);
    observer.disconnect();
    clear();
  };
}

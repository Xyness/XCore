// The server, as a block of glass.
//
// The site's block (components/Block.tsx) is the Celestis mark in three dimensions, slate faces
// with a brass edge. On the dashboard it stands for the server, so it is made of the game's own
// glass: clear panes with a pixel frame and the glints of the vanilla texture, a brass light inside
// it, and on each side face the figure it turns to show. Being glass, the far panes show through
// the near ones; the figure on a face only shows from the front.
//
// This file is only the markup. Its pose is computed in css/home.css from --t, the scene's
// position — by the scroll itself where the browser can, by pages/home.js elsewhere — and each
// face is shaded there from the same angle, so the two ways of driving it cannot disagree.

import { h, icon, brandMark } from "../lib/dom.js";

/**
 * @param {Array<{ icon: string, label: string, mark: string }>} faces The four side faces, in
 *        turn order: face k is the one presented at the k-th figure.
 */
export function glassBlock(faces) {
  const side = (face, index) =>
    h(
      "div",
      { class: "cube-face", data: { face: index }, vars: { k: index, mark: face.mark } },
      h("span", { class: "cube-pane" }, h("i", { class: "cube-shade" })),
      h("span", { class: "cube-glyph" }, icon(face.icon), h("span", { class: "cube-tag", text: face.label }))
    );

  // The light inside: three crossed planes of the same soft glow read as one orb from any angle.
  const core = h(
    "div",
    { class: "cube-core" },
    ["x", "y", "z"].map((plane) => h("span", { class: "core-plane", data: { plane } }))
  );

  return h(
    "div",
    { class: "cube-rig", "aria-hidden": "true" },
    h("span", { class: "cube-floor" }),
    h(
      "div",
      { class: "cube-scene" },
      h(
        "div",
        { class: "cube" },
        h("div", { class: "cube-face", data: { face: "bottom" } }, h("span", { class: "cube-pane" })),
        core,
        faces.slice(0, 4).map(side),
        h(
          "div",
          { class: "cube-face", data: { face: "top" } },
          h("span", { class: "cube-pane" }),
          h("span", { class: "cube-glyph cube-brand" }, brandMark())
        )
      )
    )
  );
}

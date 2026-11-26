// Měření přesnosti: pošle zkušební dopisy na server a porovná, co Claude vyčetl, s tím, co v dopise je.
// Spuštění (stojí pár korun z kreditu u Anthropicu):
//   DOPISY_URL=https://postarano-dopisy.<jmeno>.workers.dev node mereni.mjs [zakladni|presnejsi]
import { readFileSync } from "node:fs";

const url = process.env.DOPISY_URL;
if (!url) { console.error("Chybí DOPISY_URL"); process.exit(1); }
const rezimy = process.argv[2] ? [process.argv[2]] : ["zakladni", "presnejsi"];
const dopisy = JSON.parse(readFileSync(new URL("./zkusebni-dopisy.json", import.meta.url), "utf8"));

const castka = (s) => {
  const m = String(s).replace(/\s| /g, "").match(/(\d+(?:[.]\d{3})*)(?:,(\d{2}))?/);
  if (!m) return "";
  const cele = m[1].replace(/\./g, "");
  return m[2] && m[2] !== "00" ? `${cele},${m[2]}` : cele;
};
const datum = (s) => {
  const m = String(s).match(/(\d{1,2})\s?\.\s?(\d{1,2})\s?\.\s?(\d{4})/);
  return m ? `${m[3]}-${m[2].padStart(2, "0")}-${m[1].padStart(2, "0")}` : "";
};
const malymi = (s) => String(s).toLowerCase().replace(/\s+/g, " ").trim();

for (const rezim of rezimy) {
  let dobre = 0, celkem = 0;
  console.log(`\n=== ${rezim} ===`);
  for (const d of dopisy) {
    const r = await fetch(`${url}/dopis`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-postarano": "postarano-dopisy-v1" },
      body: JSON.stringify({ text: d.text, rezim, dnes: "2026-11-26" }),
    }).then((x) => x.json()).catch((e) => ({ ok: false, chyba: String(e) }));
    if (!r.ok) { console.log(`✗ ${d.nazev}: chyba ${r.chyba}`); celkem += 4; continue; }
    const v = r.vysledek, o = d.ocekavano;
    const kontroly = {
      odesilatel: o.odesilatel === "" ? v.odesilatel === "" || !v.jista
        : malymi(v.odesilatel).includes(malymi(o.odesilatel)) || (v.odesilatel && malymi(o.odesilatel).includes(malymi(v.odesilatel))),
      castka: castka(v.castka) === o.castka,
      splatnost: datum(v.splatnost) === o.splatnost,
      podvod: o.podvod === null ? null : (v.podvod.length > 0) === o.podvod,
    };
    const vysledky = Object.entries(kontroly).filter(([, x]) => x !== null);
    const ok = vysledky.filter(([, x]) => x).length;
    dobre += ok; celkem += vysledky.length;
    const spatne = vysledky.filter(([, x]) => !x).map(([k]) => `${k}: „${v[k] instanceof Array ? v[k].join("; ") : v[k]}“`);
    console.log(`${spatne.length ? "✗" : "✓"} ${d.nazev}${spatne.length ? "  →  " + spatne.join(", ") : ""}`);
  }
  console.log(`Přesnost ${rezim}: ${dobre}/${celkem} = ${Math.round((100 * dobre) / celkem)} %`);
}

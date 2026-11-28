/**
 * Postaráno: Od rodiny. Fotky, hlasové a psané vzkazy od rodiny pro seniora.
 *
 * Každý senior má svou schránku (Durable Object s malou databází SQLite). Schránku založí aplikace
 * v telefonu seniora a zapíše do ní lidi z rodiny. Každý člověk z rodiny dostane v SMS vlastní odkaz
 * na jednoduchou stránku, kde vyfotí nebo vybere fotku, nahraje hlas nebo napíše vzkaz.
 * Aplikace seniora se každých 15 minut zeptá, jestli je něco nového, a stáhne to.
 *
 * Co se ukládá: fotka / zvuk / text, jméno toho, kdo to poslal, čas, jestli to senior viděl
 * a jestli poslal srdíčko. Všechno se samo maže po 30 dnech, ve schránce je nejvýš 40 věcí.
 */
import { DurableObject } from "cloudflare:workers";

export interface RodinaEnv {
  POSTARANO_KLIC: string;
  SCHRANKY: DurableObjectNamespace<RodinnaSchranka>;
}

const DEN = 24 * 3600 * 1000;
const UCHOVAT = 30 * DEN;
const MAX_POLOZEK = 40;
const MAX_ZA_DEN = 40;
const MAX_SOUBOR = 1_500_000;
const MAX_TEXT = 300;
const ID = /^[A-Za-z0-9_-]{16,64}$/;

const POVOLENE: Record<string, string[]> = {
  foto: ["image/jpeg", "image/png", "image/webp"],
  hlas: ["audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/aac", "audio/x-m4a"],
};

type Clen = { klic: string; jmeno: string };
export type Polozka = {
  id: string; seq: number; od: string; druh: string; popis: string; mime: string;
  cas: number; videno: number; srdce: number;
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function hash(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function nahodne(): string {
  const b = crypto.getRandomValues(new Uint8Array(12));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export class RodinnaSchranka extends DurableObject<RodinaEnv> {
  private sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: RodinaEnv) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS clen (klic TEXT PRIMARY KEY, jmeno TEXT)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS polozka (
      seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE, od TEXT, druh TEXT, popis TEXT,
      mime TEXT, data BLOB, cas INTEGER, videno INTEGER DEFAULT 0, srdce INTEGER DEFAULT 0)`);
  }

  private meta(k: string): string | null {
    const r = this.sql.exec<{ v: string }>(`SELECT v FROM meta WHERE k = ?`, k).toArray();
    return r.length ? r[0].v : null;
  }

  private setMeta(k: string, v: string) {
    this.sql.exec(`INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v`, k, v);
  }

  private uklid() {
    this.sql.exec(`DELETE FROM polozka WHERE cas < ?`, Date.now() - UCHOVAT);
    this.sql.exec(`DELETE FROM polozka WHERE seq NOT IN (SELECT seq FROM polozka ORDER BY seq DESC LIMIT ?)`, MAX_POLOZEK);
  }

  /** Aplikace seniora: ověřit klíč ke čtení. Při prvním použití se klíč uloží. */
  async overSeniora(klicHash: string, zalozit: boolean): Promise<boolean> {
    const ulozeny = this.meta("cteni");
    if (ulozeny === null) {
      if (!zalozit) return false;
      this.setMeta("cteni", klicHash);
      return true;
    }
    return ulozeny === klicHash;
  }

  /** Zapsat rodinu (přepíše celý seznam) a oslovení seniora („Babička“). */
  async nastavit(senior: string, clenove: Clen[]): Promise<void> {
    this.setMeta("senior", senior.slice(0, 40));
    this.sql.exec(`DELETE FROM clen`);
    for (const c of clenove.slice(0, 10)) {
      this.sql.exec(`INSERT OR REPLACE INTO clen (klic, jmeno) VALUES (?, ?)`, c.klic, c.jmeno.slice(0, 40));
    }
  }

  async clen(klic: string): Promise<{ jmeno: string; senior: string } | null> {
    const r = this.sql.exec<{ jmeno: string }>(`SELECT jmeno FROM clen WHERE klic = ?`, klic).toArray();
    if (!r.length) return null;
    return { jmeno: r[0].jmeno, senior: this.meta("senior") ?? "" };
  }

  async pridat(od: string, druh: string, popis: string, mime: string, data: ArrayBuffer | null): Promise<string> {
    this.uklid();
    const zaDen = this.sql.exec<{ n: number }>(`SELECT COUNT(*) AS n FROM polozka WHERE cas > ?`, Date.now() - DEN).one().n;
    if (zaDen >= MAX_ZA_DEN) return "";
    const id = nahodne();
    this.sql.exec(`INSERT INTO polozka (id, od, druh, popis, mime, data, cas) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      id, od, druh, popis, mime, data, Date.now());
    this.uklid();
    return id;
  }

  /** Seznam bez souborů. po = poslední seq, kterou už aplikace má. */
  async seznam(po: number): Promise<Polozka[]> {
    this.uklid();
    return this.sql.exec<Polozka>(
      `SELECT id, seq, od, druh, popis, mime, cas, videno, srdce FROM polozka WHERE seq > ? ORDER BY seq`, po).toArray();
  }

  async posledni(): Promise<Polozka[]> {
    this.uklid();
    return this.sql.exec<Polozka>(
      `SELECT id, seq, od, druh, popis, mime, cas, videno, srdce FROM polozka ORDER BY seq DESC LIMIT 15`).toArray();
  }

  async soubor(id: string): Promise<{ mime: string; data: ArrayBuffer } | null> {
    const r = this.sql.exec<{ mime: string; data: ArrayBuffer | null }>(`SELECT mime, data FROM polozka WHERE id = ?`, id).toArray();
    if (!r.length || !r[0].data) return null;
    return { mime: r[0].mime, data: r[0].data };
  }

  async videno(id: string, srdce: boolean): Promise<void> {
    this.sql.exec(`UPDATE polozka SET videno = CASE WHEN videno = 0 THEN ? ELSE videno END WHERE id = ?`, Date.now(), id);
    if (srdce) this.sql.exec(`UPDATE polozka SET srdce = ? WHERE id = ?`, Date.now(), id);
  }
}

function schranka(env: RodinaEnv, s: string) {
  return env.SCHRANKY.get(env.SCHRANKY.idFromName(s));
}

/** Vrátí odpověď, nebo null, když adresa k Od rodiny nepatří. */
export async function rodina(req: Request, env: RodinaEnv): Promise<Response | null> {
  const url = new URL(req.url);
  const cesta = url.pathname;

  // ---------- Aplikace seniora ----------
  if (cesta.startsWith("/rodina/")) {
    if (req.headers.get("x-postarano") !== env.POSTARANO_KLIC) return json({ ok: false, chyba: "klic" }, 403);
    const s = req.headers.get("x-schranka") ?? "";
    const k = req.headers.get("x-cteni") ?? "";
    if (!ID.test(s) || k.length < 16) return json({ ok: false, chyba: "schranka" }, 400);
    const box = schranka(env, s);
    const zalozit = cesta === "/rodina/nastavit";
    if (!(await box.overSeniora(await hash(k), zalozit))) return json({ ok: false, chyba: "cteni" }, 403);

    if (zalozit && req.method === "POST") {
      const v = (await req.json().catch(() => ({}))) as { senior?: unknown; clenove?: unknown };
      const clenove: Clen[] = Array.isArray(v.clenove)
        ? v.clenove.filter((c): c is Clen => typeof c?.klic === "string" && ID.test(c.klic) && typeof c?.jmeno === "string")
        : [];
      await box.nastavit(typeof v.senior === "string" ? v.senior : "", clenove);
      return json({ ok: true });
    }
    if (cesta === "/rodina/nove" && req.method === "GET") {
      const po = Number(url.searchParams.get("po") ?? "0") || 0;
      return json({ ok: true, polozky: await box.seznam(po) });
    }
    if (cesta === "/rodina/soubor" && req.method === "GET") {
      const f = await box.soubor(url.searchParams.get("id") ?? "");
      if (!f) return json({ ok: false, chyba: "neni" }, 404);
      return new Response(f.data, { headers: { "content-type": f.mime, "cache-control": "no-store" } });
    }
    if (cesta === "/rodina/videno" && req.method === "POST") {
      const v = (await req.json().catch(() => ({}))) as { id?: unknown; srdce?: unknown };
      if (typeof v.id === "string") await box.videno(v.id, v.srdce === true);
      return json({ ok: true });
    }
    return json({ ok: false, chyba: "neznama-adresa" }, 404);
  }

  // ---------- Stránka pro rodinu: /r/<schránka>/<klíč člověka z rodiny> ----------
  const m = /^\/r\/([A-Za-z0-9_-]{16,64})\/([A-Za-z0-9_-]{16,64})(\/[a-z]+)?$/.exec(cesta);
  if (!m) return null;
  const [, s, klic, akce] = m;
  const box = schranka(env, s);
  const kdo = await box.clen(klic);
  if (!kdo) {
    return new Response(STRANKA_NEPLATNA, { status: 404, headers: { "content-type": "text/html; charset=utf-8" } });
  }

  if (!akce && req.method === "GET") {
    const html = STRANKA.replaceAll("{{SENIOR}}", esc(kdo.senior || "váš blízký")).replaceAll("{{JMENO}}", esc(kdo.jmeno));
    return new Response(html, {
      headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "referrer-policy": "no-referrer" },
    });
  }
  if (akce === "/stav" && req.method === "GET") {
    const p = await box.posledni();
    return json({ ok: true, polozky: p.map(({ id, od, druh, popis, cas, videno, srdce }) => ({ id, od, druh, popis, cas, videno, srdce })) });
  }
  if (akce === "/poslat" && req.method === "POST") {
    const delka = Number(req.headers.get("content-length") ?? "0");
    if (delka > MAX_SOUBOR + 20_000) return json({ ok: false, chyba: "velke" }, 413);
    let form: FormData;
    try { form = await req.formData(); } catch { return json({ ok: false, chyba: "spatny-dotaz" }, 400); }
    const druh = String(form.get("druh") ?? "");
    const popis = String(form.get("popis") ?? "").trim().slice(0, MAX_TEXT);
    let mime = "text/plain";
    let data: ArrayBuffer | null = null;
    if (druh === "foto" || druh === "hlas") {
      const f = form.get("soubor");
      if (!f || typeof f === "string") return json({ ok: false, chyba: "chybi-soubor" }, 400);
      mime = (f.type || "").split(";")[0].toLowerCase();
      if (!POVOLENE[druh].includes(mime)) return json({ ok: false, chyba: "typ" }, 415);
      if (f.size > MAX_SOUBOR) return json({ ok: false, chyba: "velke" }, 413);
      data = await f.arrayBuffer();
    } else if (druh === "text") {
      if (!popis) return json({ ok: false, chyba: "prazdne" }, 400);
    } else return json({ ok: false, chyba: "druh" }, 400);
    const id = await box.pridat(kdo.jmeno, druh, popis, mime, data);
    if (!id) return json({ ok: false, chyba: "moc" }, 429);
    return json({ ok: true, id });
  }
  return json({ ok: false, chyba: "neznama-adresa" }, 404);
}

function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

const STYL = `
:root{--bg:#EEF2F1;--card:#fff;--ink:#14302C;--muted:#56706B;--accent:#0F6B5C;--ok:#1D7A3A;--bad:#B3261E;--line:#CFDCD8}
@media (prefers-color-scheme:dark){:root{--bg:#0f1716;--card:#1a2422;--ink:#e6efed;--muted:#9fb5b0;--accent:#3fb39e;--ok:#5cc77b;--bad:#f2867d;--line:#2c3b38}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:18px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:20px 16px 48px}h1{font-size:28px;margin:8px 0 4px}p{margin:6px 0}
.muted{color:var(--muted);font-size:16px}.card{background:var(--card);border-radius:18px;padding:16px;margin:14px 0;border:1px solid var(--line)}
button,.btn{display:block;width:100%;border:0;border-radius:14px;padding:16px;font:700 20px system-ui,sans-serif;background:var(--accent);color:#fff;cursor:pointer;text-align:center;margin-top:10px}
button.sec{background:transparent;color:var(--accent);border:2px solid var(--accent)}button.rec{background:var(--bad)}
button:disabled{opacity:.5}textarea,input[type=text]{width:100%;font:18px system-ui,sans-serif;padding:12px;border-radius:12px;border:1px solid var(--line);background:var(--bg);color:var(--ink)}
img.nahled{max-width:100%;border-radius:12px;margin-top:10px}audio{width:100%;margin-top:10px}
.stav{font-weight:700;margin-top:10px}.ok{color:var(--ok)}.chyba{color:var(--bad)}
li{margin:8px 0;list-style:none}ul{padding:0;margin:0}input[type=file]{display:none}`;

const STRANKA_NEPLATNA = `<!doctype html><html lang="cs"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Postaráno – Od rodiny</title><style>${STYL}</style></head><body><main><h1>Odkaz už neplatí</h1>
<p>Tento odkaz nefunguje. Na telefonu seniora otevřete Postaráno, Pro rodinu, a v části Od rodiny ťukněte na Poslat odkaz znovu.</p></main></body></html>`;

const STRANKA = `<!doctype html><html lang="cs"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Postaráno – Od rodiny</title><style>${STYL}</style></head><body><main>
<h1>Pro: {{SENIOR}}</h1>
<p>Dobrý den, {{JMENO}}. Co sem pošlete, ukáže se na telefonu přes celou obrazovku a přečte se nahlas. Odpovědět může srdíčkem nebo vám zavolá.</p>

<div class="card">
  <h2>📷 Fotka</h2>
  <label class="btn" for="foto">Vyfotit nebo vybrat fotku</label>
  <input type="file" id="foto" accept="image/*">
  <img id="nahled" class="nahled" alt="" hidden>
  <p class="muted">Můžete připsat pár slov, přečtou se nahlas:</p>
  <input type="text" id="fotoPopis" maxlength="300" placeholder="Třeba: Tady je Anička na výletě">
  <button id="fotoPoslat" disabled>Poslat fotku</button>
  <p id="fotoStav" class="stav"></p>
</div>

<div class="card">
  <h2>🎤 Hlasový vzkaz</h2>
  <p class="muted">Nejvýš 1 minuta.</p>
  <button id="nahrat" class="rec">● Začít nahrávat</button>
  <audio id="poslech" controls hidden></audio>
  <button id="hlasPoslat" disabled>Poslat vzkaz</button>
  <p id="hlasStav" class="stav"></p>
</div>

<div class="card">
  <h2>✍️ Napsat vzkaz</h2>
  <textarea id="text" rows="3" maxlength="300" placeholder="Třeba: Ahoj, v neděli přijedeme na oběd."></textarea>
  <button id="textPoslat">Poslat vzkaz</button>
  <p id="textStav" class="stav"></p>
</div>

<div class="card">
  <h2>Co už odešlo</h2>
  <ul id="seznam"><li class="muted">Načítám…</li></ul>
  <button class="sec" id="obnovit">Obnovit</button>
</div>
<p class="muted">Fotky a vzkazy se samy smažou po 30 dnech. Odkaz je jen pro vás, nikomu ho neposílejte.</p>
</main>
<script>
const zaklad = location.pathname.replace(/\\/$/, "");
const $ = (id) => document.getElementById(id);
function stav(el, text, ok) { el.textContent = text; el.className = "stav " + (ok ? "ok" : "chyba"); }
async function poslat(fd, el, tlacitko) {
  tlacitko.disabled = true; stav(el, "Posílám…", true);
  try {
    const r = await fetch(zaklad + "/poslat", { method: "POST", body: fd });
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok) { stav(el, "✓ Odesláno. Na telefonu se to ukáže do 15 minut.", true); nacist(); return true; }
    const proc = { velke: "Soubor je moc velký.", typ: "Tento druh souboru neumím poslat.", moc: "Dnes už odešlo moc věcí, zkuste to zítra." }[j.chyba];
    stav(el, proc || "Nepodařilo se odeslat, zkuste to znovu.", false);
  } catch (e) { stav(el, "Není připojení k internetu, zkuste to znovu.", false); }
  tlacitko.disabled = false; return false;
}

// Fotka: zmenšit v telefonu, ať se posílá rychle
let fotka = null;
$("foto").addEventListener("change", async () => {
  const f = $("foto").files[0]; if (!f) return;
  stav($("fotoStav"), "Připravuji fotku…", true);
  try {
    const bmp = await createImageBitmap(f, { imageOrientation: "from-image" });
    const k = Math.min(1, 1600 / Math.max(bmp.width, bmp.height));
    const c = document.createElement("canvas");
    c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
    c.getContext("2d").drawImage(bmp, 0, 0, c.width, c.height);
    fotka = await new Promise((ok) => c.toBlob(ok, "image/jpeg", 0.82));
    $("nahled").src = URL.createObjectURL(fotka); $("nahled").hidden = false;
    $("fotoPoslat").disabled = false; stav($("fotoStav"), "", true);
  } catch (e) { stav($("fotoStav"), "Fotku se nepodařilo načíst.", false); }
});
$("fotoPoslat").addEventListener("click", async () => {
  if (!fotka) return;
  const fd = new FormData(); fd.append("druh", "foto"); fd.append("popis", $("fotoPopis").value); fd.append("soubor", fotka, "fotka.jpg");
  if (await poslat(fd, $("fotoStav"), $("fotoPoslat"))) { fotka = null; $("nahled").hidden = true; $("fotoPopis").value = ""; $("foto").value = ""; }
});

// Hlas
let rec = null, kusy = [], zvuk = null, casovac = null;
$("nahrat").addEventListener("click", async () => {
  if (rec && rec.state === "recording") { rec.stop(); return; }
  try {
    const proud = await navigator.mediaDevices.getUserMedia({ audio: true });
    const typ = ["audio/webm;codecs=opus", "audio/mp4", "audio/webm", "audio/ogg;codecs=opus"].find((t) => window.MediaRecorder && MediaRecorder.isTypeSupported(t)) || "";
    rec = new MediaRecorder(proud, typ ? { mimeType: typ } : undefined);
    kusy = [];
    rec.ondataavailable = (e) => { if (e.data.size) kusy.push(e.data); };
    rec.onstop = () => {
      clearTimeout(casovac); proud.getTracks().forEach((t) => t.stop());
      zvuk = new Blob(kusy, { type: (rec.mimeType || "audio/webm").split(";")[0] });
      $("poslech").src = URL.createObjectURL(zvuk); $("poslech").hidden = false;
      $("hlasPoslat").disabled = false; $("nahrat").textContent = "● Nahrát znovu";
    };
    rec.start(); $("nahrat").textContent = "■ Zastavit"; stav($("hlasStav"), "Nahrávám… mluvte.", true);
    casovac = setTimeout(() => rec.state === "recording" && rec.stop(), 60000);
  } catch (e) { stav($("hlasStav"), "Mikrofon nejde zapnout. Povolte ho v prohlížeči.", false); }
});
$("hlasPoslat").addEventListener("click", async () => {
  if (!zvuk) return;
  const fd = new FormData(); fd.append("druh", "hlas"); fd.append("soubor", zvuk, "vzkaz");
  if (await poslat(fd, $("hlasStav"), $("hlasPoslat"))) { zvuk = null; $("poslech").hidden = true; $("nahrat").textContent = "● Začít nahrávat"; }
});

// Text
$("textPoslat").addEventListener("click", async () => {
  const t = $("text").value.trim(); if (!t) return;
  const fd = new FormData(); fd.append("druh", "text"); fd.append("popis", t);
  if (await poslat(fd, $("textStav"), $("textPoslat"))) $("text").value = "";
  $("textPoslat").disabled = false;
});

// Co už odešlo
function kdy(ms) { const d = new Date(ms); return d.toLocaleDateString("cs-CZ") + " " + d.toLocaleTimeString("cs-CZ", { hour: "2-digit", minute: "2-digit" }); }
async function nacist() {
  try {
    const j = await (await fetch(zaklad + "/stav", { cache: "no-store" })).json();
    const ul = $("seznam"); ul.innerHTML = "";
    if (!j.polozky.length) { ul.innerHTML = '<li class="muted">Zatím nic.</li>'; return; }
    for (const p of j.polozky) {
      const li = document.createElement("li");
      const co = { foto: "📷 Fotka", hlas: "🎤 Hlasový vzkaz", text: "✍️ Vzkaz" }[p.druh] || p.druh;
      li.textContent = co + " · " + p.od + " · " + kdy(p.cas) + " · " + (p.videno ? "viděno ✓" : "zatím neviděno") + (p.srdce ? " · ❤️ srdíčko" : "");
      ul.appendChild(li);
    }
  } catch (e) { }
}
$("obnovit").addEventListener("click", nacist);
nacist();
</script></body></html>`;

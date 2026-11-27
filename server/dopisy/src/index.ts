/**
 * Postaráno: čtení dopisů přes Claude.
 *
 * Aplikace v telefonu dopis vyfotí a přečte (text, ne fotku) a pošle ho sem. Tady je schovaný
 * klíč k Claude, takže v aplikaci žádný klíč není. Odpověď je JSON s údaji opsanými doslova
 * z dopisu. Aplikace pak sama ověří, že každý údaj v textu dopisu opravdu je, a nehádá.
 *
 * Nic se tu neukládá: text dopisu jen projde do Claude a odpověď zpět.
 */
import Anthropic from "@anthropic-ai/sdk";

export interface Env {
  /** Tajná proměnná v Cloudflare (Settings → Variables and Secrets). */
  ANTHROPIC_API_KEY: string;
  /** Klíč aplikace z wrangler.jsonc. */
  POSTARANO_KLIC: string;
  /** Kanál v aplikaci ntfy pro upozornění správci (wrangler.jsonc). */
  NTFY_TEMA?: string;
}

/** Upozornění správci do aplikace ntfy, nejvýš jednou za 6 hodin. */
const UPOZORNENI: Record<string, { nadpis: string; text: string }> = {
  kredit: {
    nadpis: "Postaráno: došel kredit u Anthropic",
    text: "Dopisy se teď čtou jen v telefonu. Dobijte kredit: console.anthropic.com, Settings, Billing, Buy credits. " +
      "Nebo je dosažený měsíční strop útraty (Settings, Limits).",
  },
  "spatny-klic": {
    nadpis: "Postaráno: klíč k Claude nefunguje",
    text: "Dopisy se teď čtou jen v telefonu. Vytvořte nový klíč na console.anthropic.com (API Keys) a vložte ho " +
      "u Cloudflare do Settings, Variables and Secrets jako ANTHROPIC_API_KEY.",
  },
  nenastaveno: {
    nadpis: "Postaráno: chybí klíč k Claude",
    text: "U Cloudflare v Settings, Variables and Secrets chybí ANTHROPIC_API_KEY. Dopisy se čtou jen v telefonu.",
  },
};

async function upozornit(env: Env, chyba: string): Promise<void> {
  const zprava = UPOZORNENI[chyba];
  const tema = env.NTFY_TEMA;
  if (!zprava || !tema) return;
  try {
    // ntfy si zprávy pamatuje 12 hodin: když už za posledních 6 hodin jedna odešla, další neposílat
    const minule = await fetch(`https://ntfy.sh/${tema}/json?poll=1&since=6h`);
    if (minule.ok && (await minule.text()).trim()) return;
    await fetch("https://ntfy.sh/", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ topic: tema, title: zprava.nadpis, message: zprava.text, priority: 4, tags: ["warning"] }),
    });
  } catch (e) {
    console.log("Upozornění se nepodařilo poslat:", e);
  }
}

/** Základní = levný a rychlý, Přesnější = nejschopnější model. */
const MODELY = {
  zakladni: "claude-haiku-5-5",
  presnejsi: "claude-opus-5-5",
} as const;
type Rezim = keyof typeof MODELY;

const MAX_ZNAKU = 15_000;

const SYSTEM = `Čteš text papírového dopisu, který si starší člověk vyfotil mobilem. Text přečetl telefon (OCR), takže v něm můžou být chyby, rozházené řádky a tabulky čtené po sloupcích.

Tvoje odpověď rozhoduje, jestli senior zaplatí peníze. Chyba ho může stát tisíce korun, proto:
- Údaje (odesílatel, částka, splatnost, účet, variabilní symbol) opiš přesně tak, jak jsou v textu napsané, znak po znaku. Nic nedopočítávej, neopravuj ani nedomýšlej.
- Odesílatel je organizace, která dopis poslala (hlavička, logo, podpis), ne adresát ani banka v platebních údajích. Když to z textu jasně nepoznáš, nech prázdné.
- Částka je ta, kterou má senior zaplatit (k úhradě, celkem, nedoplatek). Když je jich víc a nevíš jistě, která to je, nech prázdné.
- Když údaj v dopise není nebo si nejsi jistý, dej prázdný text "". Prázdné pole je vždycky lepší než odhad.
- „jista“ nastav na false, když je text hodně poškozený nebo si nejsi jistý odesílatelem, částkou nebo termínem.
- Do „podvod“ dej jen konkrétní znaky podvodu, které v dopise opravdu jsou (chce číslo karty, PIN nebo heslo; platbu bitcoiny nebo kupónem; výhra, za kterou se platí; tváří se jako úřad nebo známá firma, ale platí se na cizí účet nebo jinam, než by čekal; odesílatel nesedí k tomu, za co chce peníze).
- Do „pozor“ dej konkrétní důvody k opatrnosti (výhrůžky, spěch, neznámý vymahač, nesrozumitelné platební údaje, exekuce, soud).
- Věty piš česky, krátce a jednoduše, jako bys to říkal babičce. Žádné odborné výrazy.`;

const SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["odesilatel", "druh", "za_co", "castka", "splatnost", "ucet", "vs", "podvod", "pozor", "shrnuti", "jista"],
  properties: {
    odesilatel: { type: "string", description: "Kdo dopis poslal, doslova z textu, nebo \"\"." },
    druh: {
      type: "string",
      enum: ["platba", "upominka", "exekuce", "soud", "preplatek", "duchod", "reklama", "informace"],
    },
    za_co: { type: "string", description: "Za co se platí, krátce ve 4. pádě (elektřinu, vodu, televizní poplatek), nebo \"\"." },
    castka: { type: "string", description: "Částka k zaplacení doslova z textu i s měnou, nebo \"\"." },
    splatnost: { type: "string", description: "Datum splatnosti doslova z textu, nebo \"\"." },
    ucet: { type: "string", description: "Číslo účtu nebo IBAN doslova z textu, nebo \"\"." },
    vs: { type: "string", description: "Variabilní symbol doslova z textu, nebo \"\"." },
    podvod: { type: "array", items: { type: "string" } },
    pozor: { type: "array", items: { type: "string" } },
    shrnuti: { type: "string", description: "Jedna jednoduchá věta, o co v dopise jde, nejvýš 20 slov." },
    jista: { type: "boolean" },
  },
} as const;

function odpoved(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const res = await obsluha(req, env);
    if (res.status >= 400) {
      const chyba = ((await res.clone().json().catch(() => ({}))) as { chyba?: string }).chyba;
      if (chyba) ctx.waitUntil(upozornit(env, chyba));
    }
    return res;
  },
};

async function obsluha(req: Request, env: Env): Promise<Response> {
  {
    const url = new URL(req.url);
    if (req.method === "GET" && url.pathname === "/") return odpoved({ ok: true, sluzba: "postarano-dopisy" });
    if (req.method !== "POST" || url.pathname !== "/dopis") return odpoved({ ok: false, chyba: "neznama-adresa" }, 404);
    if (req.headers.get("x-postarano") !== env.POSTARANO_KLIC) return odpoved({ ok: false, chyba: "klic" }, 403);
    if (!env.ANTHROPIC_API_KEY) return odpoved({ ok: false, chyba: "nenastaveno" }, 503);

    let vstup: { text?: unknown; rezim?: unknown; dnes?: unknown };
    try {
      vstup = await req.json();
    } catch {
      return odpoved({ ok: false, chyba: "spatny-dotaz" }, 400);
    }
    const text = typeof vstup.text === "string" ? vstup.text.trim() : "";
    const rezim: Rezim = vstup.rezim === "presnejsi" ? "presnejsi" : "zakladni";
    const dnes = typeof vstup.dnes === "string" && /^\d{4}-\d{2}-\d{2}$/.test(vstup.dnes) ? vstup.dnes : "";
    if (text.length < 30) return odpoved({ ok: false, chyba: "kratky-text" }, 400);
    if (text.length > MAX_ZNAKU) return odpoved({ ok: false, chyba: "dlouhy-text" }, 413);

    const model = MODELY[rezim];
    const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY, maxRetries: 1, timeout: 40_000 });
    const messages: Anthropic.MessageParam[] = [
      {
        role: "user",
        content: `${dnes ? `Dnes je ${dnes}.\n\n` : ""}Text dopisu:\n<dopis>\n${text}\n</dopis>`,
      },
    ];
    const output_config: Anthropic.OutputConfig = {
      effort: "low",
      format: { type: "json_schema", schema: SCHEMA },
    };

    try {
      let stop: string | null;
      let kusy: Array<{ type: string; text?: string }>;
      let pouzity: string;
      if (rezim === "presnejsi") {
        // Když Opus z bezpečnostních důvodů odmítne, server sám zkusí jiný model
        const r = await client.beta.messages.create({
          model,
          max_tokens: 8000,
          system: SYSTEM,
          messages,
          output_config,
          betas: ["server-side-fallback-2026-07-01"],
          fallbacks: "default",
        });
        stop = r.stop_reason; kusy = r.content; pouzity = r.model;
      } else {
        const r = await client.messages.create({ model, max_tokens: 8000, system: SYSTEM, messages, output_config });
        stop = r.stop_reason; kusy = r.content; pouzity = r.model;
      }
      if (stop === "refusal") return odpoved({ ok: false, chyba: "odmitnuto" }, 422);
      if (stop === "max_tokens") return odpoved({ ok: false, chyba: "prilis-dlouhe" }, 502);
      const json = kusy.find((b) => b.type === "text")?.text;
      if (!json) return odpoved({ ok: false, chyba: "prazdna-odpoved" }, 502);
      return odpoved({ ok: true, model: pouzity, vysledek: JSON.parse(json) });
    } catch (e) {
      if (e instanceof Anthropic.RateLimitError) return odpoved({ ok: false, chyba: "pretizeno" }, 429);
      if (e instanceof Anthropic.AuthenticationError) return odpoved({ ok: false, chyba: "spatny-klic" }, 503);
      if (e instanceof Anthropic.BadRequestError) {
        // Typicky došel kredit nebo je dosažen měsíční strop útraty
        const kredit = /credit|billing|spend/i.test(e.message);
        console.log("Claude odmítl dotaz:", e.message);
        return odpoved({ ok: false, chyba: kredit ? "kredit" : "spatny-dotaz-claude" }, kredit ? 402 : 502);
      }
      if (e instanceof Anthropic.APIConnectionError) return odpoved({ ok: false, chyba: "spojeni" }, 504);
      if (e instanceof Anthropic.APIError) {
        console.log("Chyba Claude:", e.status, e.message);
        return odpoved({ ok: false, chyba: "claude" }, 502);
      }
      console.log("Chyba:", e);
      return odpoved({ ok: false, chyba: "server" }, 500);
    }
  }
}

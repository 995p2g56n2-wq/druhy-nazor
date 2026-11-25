"""
Společný seznam podvodů pro Postaráno.

Spouští ho GitHub každých 15 minut (.github/workflows/seznam.yml). Udělá tohle:
1. Přečte nová hlášení od telefonů ze schránky ntfy.sh (číslo nebo stránka + verdikt rodiny).
2. Započítá je. Do seznamu se dostane jen to, co jako podvod nahlásí aspoň 3 různé telefony
   a výrazně víc telefonů říká „podvod“ než „v pořádku“.
3. Jednou denně stáhne veřejné seznamy: rizikové e-shopy České obchodní inspekce
   a světový seznam podvodných stránek Phishing.Database (licence MIT).
4. Zapíše hotové soubory do složky (větev „seznam“), odkud si je telefony stahují.

Čísla a stránky v server/vyjimky.txt se nikdy nezveřejní (vyřazení na žádost).

Server nezná text zpráv, jména ani čísla seniorů. Telefony se poznají jen podle náhodného označení,
a i to se ukládá jen zahashované. Dokud věc nenahlásí aspoň 3 telefony, ukládá se jen její otisk (hash),
takže omylem nahlášené číslo kamaráda se nikde neobjeví čitelně.

Použití: python seznam.py <složka s daty>
Bez internetu se dá vyzkoušet: SEZNAM_HLASENI=soubor.jsonl (řádky ve tvaru odpovědi ntfy).
"""

import datetime as dt
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.request

TOPIC = "postarano-hlaseni-v1"
NTFY = "https://ntfy.sh/"
COI_URL = "https://coi.gov.cz/pro-spotrebitele/rizikove-e-shopy/"
PHISH_URL = "https://raw.githubusercontent.com/Phishing-Database/Phishing.Database/master/phishing-domains-ACTIVE.txt"

MIN_REPORTERS = 3          # kolik různých telefonů musí nahlásit podvod
WINDOW_DAYS = 90           # starší hlášení se zapomínají (podvodníci čísla střídají)
DAILY_CAP = 20             # kolik hlášení z jednoho telefonu za den se počítá

# Stejné jako v aplikaci (SharedList.kt): sem se nikdy nic nedostane jako celá stránka
PROTECTED = {
    "google.com", "google.cz", "youtube.com", "youtu.be", "facebook.com", "fb.com", "instagram.com", "whatsapp.com",
    "apple.com", "icloud.com", "microsoft.com", "live.com", "outlook.com", "seznam.cz", "email.cz", "mapy.cz",
    "gov.cz", "mojedatovaschranka.cz", "policie.cz", "cnb.cz", "coi.gov.cz", "csob.cz", "kb.cz", "csas.cz",
    "rb.cz", "airbank.cz", "fio.cz", "moneta.cz", "mbank.cz", "unicreditbank.cz", "creditas.cz",
    "ceskaposta.cz", "postaonline.cz", "zasilkovna.cz", "packeta.com", "ppl.cz", "dpd.cz", "gls-group.eu",
    "alza.cz", "cez.cz", "o2.cz", "t-mobile.cz", "vodafone.cz", "idnes.cz", "novinky.cz", "ceskatelevize.cz",
}
# Sdílené stránky a krátké odkazy: hlásí se celý odkaz (stránka/cesta), nikdy celá stránka
SHARED = {
    "bit.ly", "tinyurl.com", "t.ly", "is.gd", "cutt.ly", "shorturl.at", "rb.gy", "goo.gl", "ow.ly", "buff.ly",
    "tiny.cc", "s.id", "t.co", "wa.me", "t.me", "linktr.ee", "qrco.de", "surl.li", "u.to", "clck.ru",
    "sites.google.com", "docs.google.com", "forms.gle", "drive.google.com", "storage.googleapis.com",
    "firebasestorage.googleapis.com", "dropbox.com", "onedrive.live.com", "1drv.ms", "temu.to",
}
# Čísla, která se do seznamu nikdy nedostanou (úřady, linky pomoci). Posledních 9 číslic.
PROTECTED_NUMBERS = {
    "974811111",  # Policie ČR, ústředna
}

# Výjimky: čísla a stránky, které někdo nechal vyřadit (omylem nahlášené). Nikdy se nezveřejní.
VYJIMKY_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vyjimky.txt")


def load_exceptions(path: str = VYJIMKY_PATH) -> set:
    out = set()
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            v = line.split("#", 1)[0].strip().lower()
            if not v:
                continue
            digits = re.sub(r"[\s+()-]", "", v)
            if digits.isdigit():
                if len(digits) >= 9:
                    out.add(digits[-9:])   # stejně jako aplikace: posledních 9 číslic
            else:
                v = re.sub(r"^https?://", "", v).removeprefix("www.").rstrip("/")
                out.add(v)
    return out


EXCEPTIONS = load_exceptions()


def excepted(x: str) -> bool:
    host = x.split("/")[0]
    return x in EXCEPTIONS or host in EXCEPTIONS or any(host.endswith("." + e) for e in EXCEPTIONS if "." in e)


HOST_RE = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}$")


def sha(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def protected_host(host: str) -> bool:
    return any(host == p or host.endswith("." + p) for p in PROTECTED)


def valid_item(t: str, x: str) -> bool:
    if excepted(x):
        return False
    if t == "n":
        return bool(re.fullmatch(r"\d{9}", x)) and x not in PROTECTED_NUMBERS
    if t == "w":
        if len(x) > 200 or any(c.isspace() for c in x):
            return False
        host, _, path = x.partition("/")
        if not HOST_RE.match(host):
            return False
        if path:
            return host in SHARED
        return host not in SHARED and not protected_host(host)
    return False


def work_ok(t: str, x: str, install: str, nonce: str) -> bool:
    d = hashlib.sha256(f"{t}:{x}|{install}|{nonce}".encode("utf-8")).digest()
    return d[0] == 0 and d[1] == 0


def fetch(url: str, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "postarano-seznam/1"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def read_reports(since: str):
    test = os.environ.get("SEZNAM_HLASENI")
    if test:
        with open(test, encoding="utf-8") as f:
            return [l for l in f.read().splitlines() if l.strip()]
    raw = fetch(f"{NTFY}{TOPIC}/json?poll=1&since={since}")
    return raw.decode("utf-8").splitlines()


def apply_reports(state: dict, lines, now: int) -> int:
    items = state.setdefault("polozky", {})
    caps = state.setdefault("limity", {})
    today = dt.datetime.fromtimestamp(now, dt.timezone.utc).strftime("%Y-%m-%d")
    counted = 0
    for line in lines:
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("event") != "message":
            continue
        state["od"] = ev.get("id") or state.get("od")
        try:
            m = json.loads(ev.get("message", ""))
            t, x, r, install, nonce = m["t"], str(m["x"]), m["r"], m["i"], str(m["p"])
        except (ValueError, KeyError, TypeError):
            continue
        if t == "w":
            host, sep, path = x.partition("/")
            x = host.lower() + sep + path
        if r not in ("s", "o") or not isinstance(install, str) or not (10 <= len(install) <= 64):
            continue
        if not valid_item(t, x) or not work_ok(t, x, install, nonce):
            continue
        who = sha("telefon:" + install)[:16]
        cap_key = f"{today}|{who}"
        if caps.get(cap_key, 0) >= DAILY_CAP:
            continue
        caps[cap_key] = caps.get(cap_key, 0) + 1
        ts = int(ev.get("time") or now)
        key = sha(f"{t}:{x}")[:32]
        it = items.setdefault(key, {"t": t, "h": {}})
        it["h"][who] = [r, ts]       # od jednoho telefonu platí jen poslední verdikt
        scam = sum(1 for v in it["h"].values() if v[0] == "s")
        if scam >= MIN_REPORTERS:
            it["x"] = x              # čitelně až po 3 nezávislých hlášeních
        counted += 1
    # Úklid: stará hlášení, vyřazená čísla a stránky, staré denní limity
    limit = now - WINDOW_DAYS * 86400
    gone = {sha(f"{t}:{e}")[:32] for e in EXCEPTIONS for t in ("n", "w")}
    for key in list(items):
        if key in gone or excepted(items[key].get("x", "")):
            del items[key]
            continue
        h = {k: v for k, v in items[key]["h"].items() if v[1] >= limit}
        if h:
            items[key]["h"] = h
        else:
            del items[key]
    for k in list(caps):
        if k.split("|")[0] < (dt.date.fromisoformat(today) - dt.timedelta(days=2)).isoformat():
            del caps[k]
    return counted


def listed(state: dict):
    nums, webs = [], []
    for it in state.get("polozky", {}).values():
        scam = sum(1 for v in it["h"].values() if v[0] == "s")
        ok = sum(1 for v in it["h"].values() if v[0] == "o")
        if "x" in it and scam >= MIN_REPORTERS and scam > 2 * ok and not excepted(it["x"]):
            (nums if it["t"] == "n" else webs).append(it["x"])
    return sorted(set(nums)), sorted(set(webs))


def parse_coi(page: str):
    out = set()
    for raw in re.findall(r"<span[^>]*>(.*?)</span>", page, flags=re.S):
        text = html.unescape(re.sub(r"<[^>]+>", "", raw)).strip().lower()
        text = re.sub(r"^https?://", "", text).removeprefix("www.").rstrip("/")
        host = text.split("/")[0]
        if HOST_RE.match(host) and not protected_host(host) and host not in SHARED and not excepted(host):
            out.add(host)
    return sorted(out)


def parse_phish(data: str):
    hosts = set()
    for line in data.splitlines():
        h = line.strip().lower()
        if not h or h.startswith("#"):
            continue
        h = h.removeprefix("www.")
        if HOST_RE.match(h) and not protected_host(h) and h not in SHARED and not excepted(h):
            hosts.add(h)
    return hosts


def phish_bin(hosts) -> bytes:
    # 5 bajtů otisku od každé stránky, seřazené (telefon hledá půlením). Asi 2 MB místo 11 MB textu.
    prefixes = sorted({hashlib.sha256(h.encode("utf-8")).digest()[:5] for h in hosts})
    return b"".join(prefixes)


CHANGE_DAYS = 30


def write_change(folder: str, pub: dict, prev, today: str, old: bytes, new: bytes):
    """
    Přírůstek za den, aby telefon nemusel každý den stahovat celé 2 MB (i bez Wi-Fi):
    zmeny/<den>.bin = 4 bajty počet přidaných, přidané otisky, odebrané otisky (po 5 bajtech).
    Řetěz v info.json říká, ze které verze na kterou se přírůstek použije.
    """
    zdir = os.path.join(folder, "zmeny")
    os.makedirs(zdir, exist_ok=True)
    chain = [c for c in pub.get("phish_retez", []) if c["den"] != today]
    if prev and old and prev != today:
        a = {old[i:i + 5] for i in range(0, len(old), 5)}
        b = {new[i:i + 5] for i in range(0, len(new), 5)}
        plus, minus = sorted(b - a), sorted(a - b)
        with open(os.path.join(zdir, today + ".bin"), "wb") as f:
            f.write(len(plus).to_bytes(4, "big") + b"".join(plus) + b"".join(minus))
        chain.append({"den": today, "z": prev, "plus": len(plus), "minus": len(minus)})
    else:
        # Bez předchozí verze (nebo podruhé týž den) řetěz nenavazuje: telefony stáhnou celý seznam
        chain = []
    limit = (dt.date.fromisoformat(today) - dt.timedelta(days=CHANGE_DAYS)).isoformat()
    chain = [c for c in chain if c["den"] > limit]
    keep = {c["den"] + ".bin" for c in chain}
    for name in os.listdir(zdir):
        if name not in keep:
            os.remove(os.path.join(zdir, name))
    pub["phish_retez"] = chain


def update_public(state: dict, folder: str, now: int, notes: list):
    pub = state.setdefault("verejne", {})
    today = dt.datetime.fromtimestamp(now, dt.timezone.utc).strftime("%Y-%m-%d")
    if pub.get("den") == today and os.environ.get("SEZNAM_VYNUTIT") != "1":
        return
    pub["den"] = today
    try:
        coi = parse_coi(fetch(COI_URL).decode("utf-8", "replace"))
        if len(coi) >= 100:      # stránka ČOI se změnila a nic nenašlo? Necháme starý seznam.
            pub["coi"] = coi
        else:
            notes.append(f"ČOI: nalezeno jen {len(coi)}, ponechán starý seznam")
    except Exception as e:  # noqa: BLE001
        notes.append(f"ČOI se nepodařilo stáhnout: {e}")
    try:
        hosts = parse_phish(fetch(PHISH_URL, 180).decode("utf-8", "replace"))
        if len(hosts) >= 10000:
            new = phish_bin(hosts)
            full = os.path.join(folder, "phish.bin")
            old = open(full, "rb").read() if os.path.exists(full) else b""
            prev = pub.get("phish_verze")
            with open(full, "wb") as f:
                f.write(new)
            write_change(folder, pub, prev, today, old, new)
            pub["phish_pocet"] = len(hosts)
            pub["phish_prefixu"] = len(new) // 5
            pub["phish_verze"] = today
        else:
            notes.append(f"Phishing.Database: jen {len(hosts)} stránek, ponechán starý seznam")
    except Exception as e:  # noqa: BLE001
        notes.append(f"Phishing.Database se nepodařilo stáhnout: {e}")


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else "seznam"
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, "stav.json")
    state = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            state = json.load(f)
    now = int(time.time())
    notes = []

    try:
        counted = apply_reports(state, read_reports(state.get("od") or "12h"), now)
    except Exception as e:  # noqa: BLE001
        counted = 0
        notes.append(f"Hlášení se nepodařilo přečíst: {e}")
    update_public(state, folder, now, notes)

    nums, community = listed(state)
    coi = state.get("verejne", {}).get("coi", [])
    with open(os.path.join(folder, "cisla.txt"), "w", encoding="utf-8") as f:
        f.write("".join(n + "\n" for n in nums))
    with open(os.path.join(folder, "weby.txt"), "w", encoding="utf-8") as f:
        f.write("".join(f"komunita {w}\n" for w in community))
        f.write("".join(f"coi {w}\n" for w in coi if w not in set(community) and not excepted(w)))
    with open(os.path.join(folder, "cisla.txt"), "rb") as a, open(os.path.join(folder, "weby.txt"), "rb") as b:
        version = hashlib.sha256(a.read() + b"|" + b.read()).hexdigest()[:12]
    info = {
        "verze": version,
        "aktualizovano": dt.datetime.fromtimestamp(now, dt.timezone.utc).isoformat(timespec="seconds"),
        "cisla": len(nums),
        "weby_komunita": len(community),
        "coi": len(coi),
        "phish": state.get("verejne", {}).get("phish_pocet", 0),
        "phish_verze": state.get("verejne", {}).get("phish_verze", ""),
        "phish_prefixu": state.get("verejne", {}).get("phish_prefixu", 0),
        "phish_retez": state.get("verejne", {}).get("phish_retez", []),
        "nova_hlaseni": counted,
        "cekajici": sum(1 for it in state.get("polozky", {}).values() if "x" not in it),
    }
    with open(os.path.join(folder, "info.json"), "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=1)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, separators=(",", ":"))
    print(json.dumps(info, ensure_ascii=False))
    for n in notes:
        print("Pozor:", n)


if __name__ == "__main__":
    main()

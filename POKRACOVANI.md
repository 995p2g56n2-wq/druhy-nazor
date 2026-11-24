# Postaráno – kde jsme skončili

*Stav k 15. 11. 2026, zkušební verze 0.8*

Plán celé aplikace je v dokumentu [Postaráno – hlavní aplikace](https://claude.ai/artifact/1nyhuWMP6dAWUwxufxSTNs).
Klikací ukázka je v [Postaráno – ukázka](https://claude.ai/artifact/2hB3qtARrYge26ztx58aer).

## Hotovo

| Verze | Co přibylo |
|---|---|
| 0.6 | Z aplikace Druhý názor je **Postaráno – rodinný štít**: úvod (pro koho), katalog pomocníků s tlačítkem *Instalovat*, průvodce povoleními po jednom kroku s hlasem, úvodní obrazovka s velkými dlaždicemi. Druhý názor je první pomocník. Aktualizace přes starou verzi, nastavení zůstává. |
| 0.7 | Rodina potvrzuje podezřelé zprávy tlačítky **Je to v pořádku / Je to podvod** v aplikaci ntfy. Babičce se zpráva obarví zeleně nebo červeně, potvrzený odkaz jde otevřít. |
| 0.8 | **Rodina nemusí nic instalovat.** Babiččin telefon posílá rodině obyčejnou SMS a rodina odpoví **ANO** / **NE** (při víc zprávách s číslem, třeba „ANO 2“). Kdo odpoví, dostane poděkování a ostatní „vyřízeno“. Rodina se vybírá z kontaktů. Zprávy od rodiny se nekontrolují. ntfy jde vypnout. Opravený useknutý název dlaždice a falešně žlutý stav po aktualizaci, číslo verze je vidět v nastavení. |

Ověřeno na babiččině telefonu (Samsung): verze 0.7 včetně tlačítek v ntfy funguje.
**Verze 0.8 je sestavená, ale v telefonu ještě nevyzkoušená.**

## Jak dostat novou verzi do telefonu

Odkaz ke stažení je pro všechny verze stejný:
<https://github.com/995p2g56n2-wq/druhy-nazor/releases/download/zkusebni-verze/DruhyNazor.apk>

1. Odkaz neposílejte babičce SMS, Druhý názor ho zablokuje. Použijte QR kód: na počítači ho otevřete a načtete fotoaparátem babiččina telefonu.
2. Po stažení ťukněte na **Otevřít** v liště nahoře a zvolte **Aktualizovat**. Starší soubor ze Stažených by nainstaloval starou verzi.
3. Kontrola: Druhý názor → úplně dole „Verze aplikace: 0.8-zkusebni“.

Nová verze vznikne automaticky po každém sloučení do větve `main`.

## Co vyzkoušet jako první (verze 0.8)

- [ ] Druhý názor → karta **Komu z rodiny psát**: je tam Standa?
- [ ] Ťuknout na **Poslat rodině zkušební SMS**: přišla SMS?
- [ ] Z jiného čísla než Standova poslat babičce SMS s odkazem. Přišla Standovi SMS s otázkou?
- [ ] Odpovědět **ANO**. Zbarví se zpráva u babičky zeleně a jde otevřít odkaz? Přišlo poděkování?
- [ ] Zkusit totéž s **NE**: zčervená zpráva?
- [ ] Rozhodnout, jestli nechat zapnuté i ntfy, nebo stačí SMS.

## Další kroky podle plánu

1. Výsledky testu 0.8 a opravy podle nich.
2. **Energie**: vyfotit fakturu nebo elektroměr a dostat lidské vysvětlení, týdenní zapisování stavů.
3. **Pomoc s technikou**: otázka hlasem, rada krok za krokem.
4. Vyzkoušet s 5 seniory a jejich dětmi.
5. Později: Postaráno i pro rodinu (tlačítka místo SMS, přidávání pomocníků na dálku). Bude to potřebovat malý vlastní server.
6. Pro Hlídač peněz najít partnera s licencí ČNB.

## Otevřené otázky

- Konečný název aplikace. „Postaráno – rodinný štít“ je pracovní.
- iPhone: u seniora jde hlídání hovorů a SMS naplno jen na Androidu. Je potřeba to lidem říct dopředu.
- Google Play bude chtít zdůvodnit roli aplikace na SMS (schvalovací proces).
- SMS rodině jdou z tarifu seniora. Pro běžné tarify to nevadí.

## Pro vývoj

- Repozitář `995p2g56n2-wq/druhy-nazor`. Android, Kotlin, bez knihoven, obrazovky skládané v kódu (`Ui.kt`).
- Hlavní soubory: `MainActivity` (úvod, katalog, dlaždice), `DnSetupActivity` (průvodce povoleními a rodina), `DruhyNazorActivity` (zprávy a nastavení), `Modules` (katalog), `Family` + `FamilyUi` (SMS rodině, výběr kontaktů), `Verdicts` (odpovědi ANO/NE a ntfy).
- ID aplikace zůstává `cz.druhynazor.test`, aby šla aktualizovat přes starou verzi.

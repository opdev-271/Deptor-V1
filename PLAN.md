# Deptor — plán implementace (Android)

Aplikace na sledování společných výdajů ve skupině a výpočet minimálního počtu plateb
k vyrovnání dluhů. Vzor: Settle Up. Zdroj zadání: `Deptor - prompt 1.docx`.

## Rozhodnutí

| Oblast | Volba |
|---|---|
| Platforma | **Android only** (iOS/web mimo rozsah, ale doménová vrstva zůstává čistý Kotlin) |
| UI | Kotlin + Jetpack Compose, Material 3, Navigation Compose |
| Architektura | MVVM + UDF (StateFlow), vrstvy `ui / domain / data` |
| DI | Hilt |
| Backend | Firebase — Auth, Firestore (offline persistence + realtime), Storage (účtenky), FCM |
| Lokální DB | Firestore offline cache pro MVP; Room jen pokud narazíme na limity dotazů |
| Peníze | `Long` v minor units (haléře/centy) + ISO kód měny. **Nikdy `Double`.** |
| Testy | JUnit + kotlin.test na doméně, Turbine na flows, Compose UI testy na klíčové obrazovky |
| Min SDK | 26 (java.time bez desugaringu) / compile a target 36 |

Konkrétní verze a setup Firebase viz [SETUP.md](SETUP.md).

## Rozsah 1. iterace (MVP, bod 14 kroky 1–4)

1. Autentizace + vytvoření skupiny + přidání členů (včetně virtuálních)
2. Přidání výdaje s rozdělením **rovným dílem**
3. Výpočet a zobrazení bilance („kdo komu dluží")
4. Označení dluhu jako vyrovnaného

Mimo MVP (další iterace): přesné částky/procenta/váhy/více plátců, itemized bill,
multiměna, export, kategorie, notifikace, sdílené odkazy, QR, opakující se transakce.

---

## Datový model (Firestore)

```
users/{uid}
  displayName, email, photoUrl, createdAt

groups/{groupId}
  name, currency, icon, color, ownerUid, archived, createdAt
  memberUids: [uid]            // pro security rules a dotaz "moje skupiny"

groups/{groupId}/members/{memberId}
  name, uid|null,              // null = virtuální člen, později propojitelný
  defaultWeight: Int = 1, active: Bool

groups/{groupId}/transactions/{txId}
  type: EXPENSE | SETTLEMENT | INCOME
  amountMinor: Long, currency: String
  date: Timestamp, description: String, categoryId: String?
  payers:  [{ memberId, amountMinor }]        // více plátců podporováno od začátku ve schématu
  splits:  [{ memberId, amountMinor }]        // vždy uložené jako výsledné částky
  splitMethod: EQUAL | EXACT | PERCENT | SHARES
  splitConfig: Map                            // vstupy pro re-edit (váhy, procenta)
  createdBy, createdAt, updatedAt
```

**Klíčové rozhodnutí:** `splits` se ukládají jako **konkrétní částky**, nikoli jako pravidlo.
Rozdělení se spočítá při zadání a uloží. Bilance je pak čistý součet a nezávisí na tom,
jaká byla metoda. `splitMethod` + `splitConfig` slouží jen k rekonstrukci formuláře při editaci.

Invariant vynucený v doméně i v security rules: `sum(payers.amountMinor) == sum(splits.amountMinor) == amountMinor`.

**Settlement** je transakce typu `SETTLEMENT`: `payers = [{od, částka}]`, `splits = [{komu, částka}]`.
Díky tomu prochází stejným výpočtem bilance a nepotřebuje zvláštní větev.

Balance se **nikdy neukládá** — je odvozená (`sum(payers) − sum(splits)` per člen).

---

## Doména — dva algoritmy, které musí být neprůstřelné

### 1. Rozdělení částky bez ztráty haléřů

```kotlin
fun splitEqually(totalMinor: Long, memberIds: List<MemberId>): List<Split>
```
Celočíselné dělení + zbytek se deterministicky rozdistribuuje po 1 minor unit
(stabilní pořadí podle memberId, ne náhodně — jinak se výsledek mění mezi zařízeními).
Totéž pro váhy a procenta (largest-remainder metoda).
Test: pro libovolné `total` a `n` platí `sum(splits) == total`.

### 2. Zjednodušení dluhů

```kotlin
fun simplify(balances: Map<MemberId, Long>): List<Transfer>
```
Greedy: max věřitel ↔ max dlužník (dvě prioritní fronty), převod `min(|a|,|b|)`,
opakovat dokud nejsou všechna salda nulová. Dá `≤ n−1` transakcí.
Testy: součet sald == 0, žádný transfer ≤ 0, po aplikaci transferů jsou salda nulová.

Obojí jsou čisté funkce bez Androidu → pokryté unit testy jako první věc.

---

## Obrazovky (MVP)

| Obrazovka | Obsah |
|---|---|
| Auth | e-mail + Google sign-in, anonymní vstup |
| Skupiny | seznam skupin, souhrn „dlužíš / dluží ti", FAB nová skupina |
| Detail skupiny | taby: **Výdaje** (chronologický seznam) / **Bilance** (bubliny) |
| Přidat výdaj | částka (s kalkulačkou), popis, datum, kdo platil, komu se dělí, rovným dílem |
| Bilance | salda členů + navrhované převody ze `simplify()` |
| Vyrovnat | předvyplněný settlement z navrženého převodu |
| Členové | přidat člena jménem (virtuální) / pozvat e-mailem |

---

## Postup prací

**Fáze 0 — základ** ✅ hotovo
1. ✅ Android projekt, Gradle version catalog, Compose, Hilt, moduly `:app :domain :data`
2. ⏳ Firebase projekt a `google-services.json` — čeká na uživatele, viz [SETUP.md](SETUP.md).
   Build je zařízený tak, že bez toho souboru projde a Firebase se sám zapne, až přibude.
3. ✅ Firestore security rules (`firebase/firestore.rules`) — testy rules na emulátoru zbývají

**Fáze 1 — doména (bez UI, čistě testy)** ✅ hotovo — 47 testů
4. ✅ Modely `Money`, `Member`, `Transaction`, `Balance`
5. ✅ `distribute` + `ExpenseSplitter` (equal/weights/percent/exact) + testy na zachování součtu
6. ✅ `computeBalances` + `simplify` + testy

**Fáze 2 — data** ✅ hotovo — 15 JVM testů mapperu + 15 instrumentovaných proti emulátoru
7. ✅ Repozitáře nad Firestore, mapování DTO ↔ doména, `Flow` z realtime listenerů
8. ✅ Auth repozitář (e-mail, anonymní + povýšení anonymního účtu se zachováním UID).
   Google sign-in zbývá — potřebuje reálný Firebase projekt a SHA-1.

**Fáze 3 — UI**
9. ✅ Auth flow → Skupiny → Detail skupiny → Členové
10. ✅ Přidat výdaj (equal split) s náhledem rozdělení
11. ✅ Bilance se salda + návrhy převodů ze `simplify()` + zápis vyrovnání

Formátování a parsování částek řeší `MoneyFormat` v `:domain` — jediné místo v aplikaci,
kde se pracuje s desetinnými čísly.

**Fáze 5 — rozúčtování** ✅ hotovo
15. ✅ Přesné částky, procenta, podíly a více plátců v UI (`AddExpenseUiState`)
16. ✅ `PercentFormat` — procenta jako celá čísla v setinách procenta

Procenta se drží jako basis points (33,33 % = 3333), ne jako desetinná čísla: součet
musí vyjít přesně na 100 %, jinak by rozúčtování o haléře nesedělo. Normalizaci
uživatelského vstupu sdílí `DecimalInput` s `MoneyFormat`.

Přepnutí metody vstupy **nemaže** a předvyplní je tak, aby byly rovnou uložitelné —
u tří lidí to znamená 33,33 / 33,33 / 33,34, tedy zase metoda největšího zbytku.

**Fáze 4 — dotažení MVP** ✅ hotovo
12. ✅ Offline ověření — `OfflineSyncTest` přes `disableNetwork()`/`enableNetwork()`.
    Stav serveru se čte přes REST **mimo Firestore SDK**; přes SDK by test četl lokální
    cache a prošel by i tehdy, kdyby se na server nikdy nic nedostalo.
13. ✅ Empty states, chyby, loading, dark mode
14. ✅ Testy end-to-end na hlavní scénář — `GroupDetailViewModelTest` a
    `AddExpenseViewModelTest` nad fake repozitáři, běží na JVM bez emulátoru.

### Zápisy se nečekají na potvrzení serverem

Task z `set()`/`update()`/`delete()` se dokončí **až po potvrzení serverem**, tedy offline
nikdy. Kdyby na něj repozitář čekal, přidání výdaje bez signálu by zamrzlo na spinneru,
přestože Firestore data okamžitě uloží lokálně a listener je hned vrátí do UI. Zápisy
proto končí `reportFailureAsync()`, ne `await()`.

Výjimka je `createGroup`: security rules se vyhodnocují na serveru a u podkolekcí skupiny
sahají `get()` na její dokument. Dokud tam skupina není, listener na její výdaje dostane
`PERMISSION_DENIED`. Vytvoření skupiny se proto čeká — a tím pádem **offline nefunguje**.
Přidávání výdajů, na kterém offline režim ze zadání stojí, funguje bez omezení.

Cenou za neblokující zápisy je, že se pozdější selhání (typicky `PERMISSION_DENIED`)
uživateli nedostane, jen do logu. Až bude potřeba je ukazovat, patří sem kanál chyb do UI.

Pozor na past ve stavech ViewModelů: `state` je `stateIn(WhileSubscribed)`, takže bez
odběratele se upstream vůbec nespustí a `state.value` zůstane na výchozí prázdné hodnotě.
Test, který si odběr nenaváže, pak nad prázdnými seznamy projde, aniž by cokoli ověřil.

### Export do CSV

Oddělovač je **středník**, ne čárka: desetinná čárka je v českém prostředí norma a
s čárkou jako oddělovačem by se soubor rozpadl hned na první částce.

Textové hodnoty začínající na `=`, `+`, `-` nebo `@` dostanou před sebe apostrof.
Bez toho by se v Excelu i v Google Sheets vyhodnotily jako **vzorec** — a popis výdaje
píše kdokoli ze skupiny, takže by přes export šlo spustit cizímu člověku kód v tabulce.

Soubor se předává přes `FileProvider`; předání `file://` URI jiné aplikaci od Androidu 7
skončí výjimkou. Leží v cache, protože je to jednorázová příloha, ne dokument ke správě.

Export do PDF a Excelu ze zadání zatím není — CSV otevře Excel i Sheets, PDF by
znamenalo sázet tabulku ručně přes `PdfDocument`.

### Pozvánky a QR

Pozvánkový kód **je oprávnění** — kdo ho zná, může do skupiny vstoupit. Ověřit znalost
kódu jde i bez Cloud Functions: nečlen skupinu přečíst nesmí, takže `inviteCode` nemá
jak zjistit. Klient proto pošle kód, který zná, v poli `joinToken`, a security rule ho
porovná s uloženým `inviteCode`. Server tím znalost ověří, aniž by kód kdy vydal ven.

Pravidlo zároveň hlídá, že zápis nedělá nic jiného než přidání volajícího do
`memberUids` — jinak by pozvánka byla vstupenka k přepsání názvu, měny nebo vlastníka.
Obojí má instrumentovaný test proti skutečným rules.

Odkaz používá vlastní schéma `deptor://`, ne https. Klikatelný odkaz z prohlížeče nebo
z náhledu ve WhatsAppu vyžaduje vlastní doménu a ověření App Links — to je práce
navázaná na webovou verzi, která zatím neexistuje.

**Read-only web pro nečleny (spec §9) chybí.** Ukázat bilanci v prohlížeči bez instalace
znamená webovou aplikaci a hosting, což je mimo „zatím jen Android".

### Rozpad účtu na položky

Každý platí jen to, co si dal; sdílená položka se dělí rovným dílem mezi své konzumenty.
**Daň a spropitné se rozpočítají poměrně podle útraty**, ne rovným dílem — jinak by ten,
kdo si dal jen polévku, doplácel na cizí víno.

Celková částka se u téhle metody **nezadává, ale dopočítává** ze součtu položek, daně
a spropitného. Dvě čísla zvlášť by se mohla rozejít a účtenku uživatel stejně opisuje
po položkách.

Položky mají stabilní klíč, ne index: po smazání prostřední položky by se indexy
přeházely a Compose by prohodil obsah textových polí.

### Kategorie

Seznam kategorií je **pevný**, ne uživatelsky editovatelný. Vlastní kategorie by znamenaly
další kolekci ve Firestore, správu duplicit mezi členy a slučování při synchronizaci —
a přehled „na co utrácíme" funguje dobře i nad pevnou sadou.

Do databáze jde **stabilní řetězcové id**, ne pořadí v enumu: jinak by přidání kategorie
doprostřed přeznačilo už uložené výdaje. Neznámé id (třeba od novější verze aplikace)
se chová jako nezařazený výdaj, ne jako pád.

Názvy a ikony jsou v UI vrstvě, doména o vzhledu neví.

Do přehledu se počítají **jen výdaje**. Vyrovnání je přesun peněz mezi lidmi, ne útrata —
kdyby se počítalo, „utratili jsme" by rostlo pokaždé, když si někdo pošle dluh zpátky.

### Multiměna

Kurz se ukládá **k transakci** a nikdy se nepřepočítává. Kdyby se salda počítala vždy
aktuálním kurzem, pohnul by se i dluh, který spolu lidé už vyrovnali, a částka, na které
se domluvili, by po týdnu seděla jinak.

Převádí se **jen celková částka**, a ta se pak mezi plátce a podíly rozdělí v původním
poměru metodou největšího zbytku. Kdyby se převáděl každý podíl zvlášť, zaokrouhlení by
se u každého posunulo jinam a součty by přestaly sedět na celek — padl by invariant,
na kterém stojí celý výpočet sald.

`exchangeRate` je nullable schválně: kdyby chybějící kurz mlčky znamenal 1, transakce
v eurech bez kurzu by se do sald započítala jako koruny a nikdo by si toho nevšiml.
Ve Firestore se kurz drží jako **text**, protože Firestore umí jen `Double` a ten by
u kurzu typu 24,375 zanesl nepřesnost hned na vstupu.

Kurzy chodí z Frankfurteru (ECB, bez API klíče) za rozhraním `ExchangeRateRepository`,
takže se zdroj dá vyměnit. Stažený kurz se cachuje a offline se použije poslední známý —
což je důležitější, než se zdá: cizí měnu člověk zadává hlavně na cestách. Kurz je vždy
přepsatelný ručně, jinak by bez signálu nešel výdaj uložit vůbec.

### Párové dluhy

`computePairwiseDebts` drží **skutečné vztahy**, kdežto `simplify()` navrhuje nejkratší
cestu k vyrovnání a klidně pošle peníze někomu, s kým dlužník nikdy nic neměl. Obojí je
správně, každé odpovídá na jinou otázku.

Podíl každého člena se rozpočítá mezi plátce v poměru jejich vkladů — zase metodou
největšího zbytku, aby se neztrácely haléře — a vzájemné dluhy se vynetují. Platí
invariant, že součet párových dluhů člena odpovídá jeho saldu; je na to property test
nad 300 náhodnými scénáři, protože kdyby se ta dvě čísla rozešla, uživatel by v detailu
člena viděl něco jiného než v bilanci.

### Opakující se transakce

Generuje se **na zařízeních členů**, ne na serveru — ten by znamenal Cloud Functions
a placený tarif. Pravidlo se tedy projeví, až někdo aplikaci otevře; zameškaná období
se dohánějí zpětně, takže se nic neztratí, jen se to objeví později.

Proti duplicitám chrání **deterministické id** transakce, odvozené z pravidla a termínu
(`rec_<pravidlo>_<datum>`). Když stejný nájem vygenerují dvě zařízení naráz, druhý zápis
jen přepíše ten první. Kvůli tomu `addTransaction` respektuje vyplněné id místo aby vždy
generovalo nové — pozor, prázdné id znamená „přiděl nové".

Termíny se počítají **vždy od začátku pravidla**, ne přičítáním k předchozímu: nájem od
31. ledna má padnout na 28. února a pak zase na 31. března. Přičítáním by únor datum
ořízl a od té chvíle by nájem chodil 28. každý měsíc.

Zrušení pravidla nemaže už vzniklé výdaje — jsou to skutečné platby a smazat je by
rozhodilo salda.

## Známé problémy

- **`./gradlew build` občas spadne hned po vypnutí emulátorů** — prázdný chybový výstup,
  pád do ~13 s (tedy ve fázi konfigurace), opakované spuštění vždy projde. Za session
  se to stalo třikrát. Nepodařilo se reprodukovat ani po `--stop`, smazání
  `configuration-cache` ani po změně zdrojáku. Příčina neznámá.

## Odložené, ať se na to nezapomene
- **Invariant součtů v security rules nejde vynutit** — jazyk rules nemá smyčky ani
  reduce, takže pole libovolné délky v něm sečíst nelze. Hlídá to doména při zápisu
  (`Transaction.init`) i při čtení (mapper odmítne vadný dokument a vrátí ho jako
  `RejectedDocument`). Kdyby to jednou nestačilo, správné místo je Cloud Function.
- **Google sign-in** — kód i UI zbývají, potřebuje reálný Firebase projekt a SHA-1.
- **Přetečení `Long`**: `distribute` hlásí `ArithmeticException` místo tichého přetečení.
  U reálných částek nenastane; kdyby přece, UI to musí odchytit jako chybu zadání.

## Otevřené otázky (nutné rozhodnout před fází 2)

- **Podpis a Google sign-in**: potřebuju SHA-1 debug keystore do Firebase konzole — uděláš ty.
- **Anonymní režim**: spec chce lokální použití bez účtu. Firebase Anonymous Auth to řeší
  včetně pozdějšího upgradu na plný účet — navrhuji to, ne vlastní lokální režim.
- **Konflikty**: Firestore je last-write-wins. Pro transakce to stačí (každá je vlastní
  dokument). Riziko je jen souběžná editace téhož výdaje — pro MVP akceptuji.
- **Měna**: MVP jen jedna měna na skupinu, žádné kurzy.

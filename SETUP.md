# Setup

## Sestavení

Projekt používá Gradle wrapper, takže stačí JDK 21+ a Android SDK.

Na tomhle stroji **není samostatná Java** — jediný JDK je ten zabalený v Android Studiu.
Z terminálu je proto potřeba nastavit `JAVA_HOME`:

```bash
export JAVA_HOME=/snap/android-studio/236/jbr
```

Pozor: číslo `236` je revize snapu a po aktualizaci Android Studia se změní. Pokud build
přestane fungovat s „java: command not found", buď cestu oprav, nebo si nainstaluj
samostatný JDK a problém zmizí natrvalo:

```bash
sudo apt install openjdk-21-jdk
```

Z Android Studia nic nastavovat netřeba, to používá svůj vlastní JDK automaticky.

Build a testy:

```bash
./gradlew :domain:test
```

```bash
./gradlew :app:assembleDebug
```

## Verze

| | |
|---|---|
| AGP | 9.0.1 — Kotlin je od AGP 9 vestavěný, plugin `kotlin-android` se **neaplikuje** |
| Gradle | 9.1.0 |
| Kotlin | 2.3.20 |
| KSP | 2.3.11 (od KSP 2.3 vlastní verzování, nezávislé na Kotlinu) |
| Hilt | 2.60.1 — vyžaduje AGP 9+ |
| compileSdk / minSdk / targetSdk | 36 / 26 / 36 |

`minSdk 26` je zvolený kvůli `java.time` bez desugaringu.

## Testy proti emulátoru (bez Firebase účtu)

Instrumentované testy **nepotřebují Firebase projekt ani `google-services.json`.**
`EmulatorFirebase` si sestaví `FirebaseOptions` ručně a projectId `demo-deptor` emulátor
pozná jako demo projekt, který se nikdy nepokouší volat produkční službu.

Emulátor načítá `firebase/firestore.rules`, takže testy pokrývají i security rules —
`SecurityRulesTest` ověřuje, že se nečlen ke skupině nedostane.

Potřebné je Firebase CLI. Standalone binárka, bez node a bez sudo:

```bash
curl -sSL -o ~/.local/bin/firebase https://firebase.tools/bin/linux/latest && chmod +x ~/.local/bin/firebase
```

Pak nastartuj Android zařízení a spusť skript — Firebase emulátor si obstará sám.
Android emulátor je náročný na CPU, tohle je odlehčená konfigurace:

```bash
$ANDROID_HOME/emulator/emulator -avd Pixel_4 -no-window -no-audio -no-boot-anim -cores 2 -memory 1536 -gpu swiftshader_indirect &
```

```bash
./scripts/test-emulator.sh
```

Ruční varianta, když chceš emulátor nechat běžet mezi běhy:

```bash
firebase emulators:start --only firestore,auth --project demo-deptor
```

```bash
./gradlew :data:connectedDebugAndroidTest
```

Emulator UI běží na <http://127.0.0.1:4000> a je v něm vidět, co testy do Firestore
zapsaly — užitečné při ladění.

**Pozor na cleartext HTTP.** Emulátor mluví plain HTTP a Android to od API 28 blokuje.
Výjimka je v `data/src/androidTest/res/xml/emulator_network_config.xml`, omezená na
`10.0.2.2` a jen v testovacím source setu — do produkční aplikace se nedostane.

**Pozor na `runTest` u integračních testů.** `runTest` pracuje s virtuálním časem:
`delay()` se přeskočí a `withTimeout` vyprší okamžitě, takže reálná síť nikdy nedostane
šanci doběhnout. Testy, které sahají na skutečné I/O (`OfflineSyncTest`), proto běží na
`runBlocking`. JUnit4 navíc vyžaduje `void`, takže je potřeba `runBlocking<Unit> { }` —
jinak se celá třída neprojeví jako neúspěšný test, ale jako `initializationError`.

## Spuštění aplikace proti emulátoru

Debug build umí běžet i bez Firebase projektu. Když `app/google-services.json` chybí,
`FirebaseBootstrap` nastaví Firebase ručně na lokální emulátor — aplikace jde tedy
proklikat ještě předtím, než vůbec Firebase projekt vznikne.

Release build tohle **nedělá**: chybějící konfigurace je tam skutečná chyba a je lepší,
aby se projevila, než aby produkční verze potichu mířila na localhost.

```bash
firebase emulators:start --only firestore,auth --project demo-deptor
```

```bash
./gradlew :app:installDebug && adb shell am start -n dev.op.deptor/.MainActivity
```

Android emulátor je náročný na CPU. Lehčí konfigurace:

```bash
$ANDROID_HOME/emulator/emulator -avd Pixel_4 -no-window -no-audio -no-boot-anim -cores 2 -memory 1536 -gpu swiftshader_indirect
```

## Firebase — kroky, které musíš udělat ty

Tohle je potřeba až pro **běh aplikace na reálném Firebase**. Testy se bez toho obejdou
(viz sekce výše). Projekt se sestaví i bez Firebase; plugin `google-services` se aplikuje
jen tehdy, když v `app/` existuje `google-services.json` (viz `app/build.gradle.kts`).

1. Založ projekt na <https://console.firebase.google.com>.
2. Přidej Android aplikaci s package name **`dev.op.deptor`**.
3. Získej SHA-1 debug keystoru a vlož ho do nastavení aplikace v konzoli —
   **bez toho nebude fungovat přihlášení přes Google**:

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```

4. Stáhni `google-services.json` a ulož ho do `app/google-services.json`.
   Soubor je v `.gitignore` — nepatří do repozitáře.
5. V konzoli zapni:
   - **Authentication** → poskytovatele Email/Password, Google, Anonymous
   - **Firestore Database** (produkční režim)
6. Nasaď pravidla a indexy z `firebase/`:

   ```bash
   firebase deploy --only firestore:rules,firestore:indexes
   ```

## Struktura

```
domain/   čistý Kotlin, žádný Android — modely, rozdělování částek, výpočet sald
data/     Android library — Firestore repozitáře, mapování na doménu (fáze 2)
app/      Compose UI, navigace, ViewModely (fáze 3)
```

Závislosti jdou jen jedním směrem: `app → data → domain`. Doména nesmí nikdy začít
záviset na Androidu ani na Firebase — díky tomu jsou její testy obyčejné JVM testy
a běží v jednotkách sekund.

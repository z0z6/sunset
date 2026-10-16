# SunsetCheck

Androidowa aplikacja (Kotlin, Jetpack Compose), która szacuje szansę zobaczenia zachodu słońca
w wybranym punkcie (mapa OSM lub GPS) na terenie Polski.

## Jak to liczy
1. Pozycja Słońca (algorytm astronomiczny) → czas i azymut zachodu.
2. Profil terenu w kierunku zachodu (Open-Meteo Elevation, DEM 90 m) → horyzont terenowy.
3. Prognozy 4 modeli (ECMWF, ICON, GFS, Météo-France) pobierane **wzdłuż azymutu słońca**
   (0–300 km), bo promień przy horyzoncie przechodzi przez chmury niskie w odległości do ~80 km,
   średnie do ~190 km, wysokie do ~300 km (z uwzględnieniem krzywizny Ziemi).
4. Wynik 1–99 = średnia z modeli, osobno „pewność prognozy” (zgodność modeli + horyzont czasowy).

**Nie ma „100%”** – to szacunek probabilistyczny. Współczynniki w `Analyzer.kt`
(`blocked()`, przezroczystość warstw, kary za opady/widzialność) są heurystyczne i warto je
kalibrować na własnych obserwacjach.

## Build
- GitHub Actions: `.github/workflows/build.yml` buduje `assembleDebug` i wrzuca APK jako artefakt.
- Lokalnie: Android Studio (JDK 17) lub `gradle assembleDebug`.
- (opcjonalnie) `gradle wrapper --gradle-version 8.10.2` i commit `gradlew` + `gradle/wrapper`.

## Uwagi
- Kafelki map: serwer OSM (tile.openstreetmap.org) – do użytku osobistego OK; przy publikacji
  zmień dostawcę kafelków zgodnie z polityką OSM.
- Brak własnej ikony launchera (domyślna ikona systemowa).

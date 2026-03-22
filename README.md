# FlightVisualizer

Android aplikácia na vizualizáciu letových záznamov. Umožňuje načítať telemetrické dáta z rôznych zdrojov, prehrať ich na interaktívnej mape a zobraziť letové parametre na HUD displeji.

## Funkcie

- Prehrávanie letového záznamu na satelitnej mape (Google Maps)
- HUD displej s hodnotami výšky, rýchlosti, vertikálnej rýchlosti, kurzu a náklonu
- Podpora viacerých formátov letových záznamov
- Dva režimy zobrazenia dát: RAW a ASSISTED

## Podporované formáty

| Formát | Zdroj |
|--------|-------|
| KML | WIW GPS logger / Google Earth |
| CSV | Microsoft Flight Simulator (SkyDolly) |
| CSV | Garmin avionics (G1000 / G3X Touch) |
| CSV | DJI FlightRecord |
| CSV | AirData UAV |
| TXT | Arduino GPS + IMU logger |

## Požiadavky

- Android 8.0 (API 26) a vyššie
- Google Play Services

## Inštalácia

1. Stihnite APK zo sekcie [Releases](../../releases)
2. Povoľte inštaláciu z neznámych zdrojov
3. Nainštalujte APK

### Spustenie zo zdrojového kódu

1. Klonujte repozitár
2. Vygenerujte Google Maps API kľúč na [Google Cloud Console](https://console.cloud.google.com/)
3. Pridajte kľúč do `local.properties`:
   ```
   MAPS_API_KEY=vas_api_kluc
   ```
4. Buildujte a spustite cez Android Studio

## Architektúra

```
app/
├── core/           # FlightHelper, FlightLoader, GeoMath
├── data/
│   ├── model/      # FlightPoint, LogType
│   ├── normalize/  # DataNormalizer
│   └── parser/     # Parsery pre jednotlivé formáty
└── ui/
    ├── importdata/ # DataSummaryActivity
    ├── main/       # MainActivity, StartActivity
    └── widgets/    # AttitudeHudView
```

## Autor

Peter Dúbrava — bakalárska práca Mobilná aplikácia s geolokačnými
a mapovými prvkami, UKF Nitra

# TiempoDroid

En el móvil se llama **Tiempo ES**. El tiempo de **AEMET** (o de **Open-Meteo** sin clave), con
avisos, sol y luna, mareas y un widget con color según la temperatura.

Versión para Android de [Tiempo ES](https://github.com/baltamir1978/AEMET-Netatmo-ios) (iOS),
escrita desde cero en Kotlin + Jetpack Compose. Funciona sin Google Play (GrapheneOS): ubica y
busca sin geocodificador, con el nomenclátor dentro de la app.

**Estado: 0.1** (5-nov-2026). Ver `CLAUDE.md` para lo que falta (Netatmo, Portugal…).

## Qué hace

- Predicción por horas y días, temperatura de la estación más cercana y **avisos** de AEMET.
- **Sin clave de AEMET, todo sale de Open-Meteo** con la misma pantalla.
- **Una página por ubicación** (la del GPS y las que sigas); el pueblo, no sólo el municipio.
- **Buscador instantáneo** de municipios y pueblos, sin red.
- Aire, polen y UV.
- **Sol y luna** (orto, ocaso, fase, próximas fases) y **mareas del IHM** en la hora del puerto.
- **Widget** con la ciudad y el fondo a elegir, y seis escalas de color según la temperatura.

## Compilar

```sh
Tools/instalar-entorno.sh      # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
echo "aemet.apiKey=…" >> local.properties    # opcional
./gradlew testDebugUnitTest assembleRelease
```

## Datos

© AEMET · Open-Meteo (CC BY 4.0) · © Instituto Hidrográfico de la Marina · pueblos y aldeas de
[GeoNames](https://www.geonames.org/) (CC BY 4.0).

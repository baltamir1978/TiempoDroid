# TiempoDroid

En el móvil se llama **Tiempo ES**. El tiempo de **AEMET** (o de **Open-Meteo** sin clave) y de
Portugal con el **IPMA**, con avisos, sol y luna, mareas, tu estación **Netatmo** y cuatro widgets.

Versión para Android de [Tiempo ES](https://github.com/baltamir1978/AEMET-Netatmo-ios) (iOS),
escrita desde cero en Kotlin + Jetpack Compose. Funciona sin Google Play (GrapheneOS): ubica y
busca sin geocodificador, con el nomenclátor y los términos municipales dentro de la app.

**Estado: 0.3.1** (5-nov-2026): está todo lo de la app de iOS, sin probar todavía en el móvil.
Ver `CLAUDE.md`.

## Qué hace

- Predicción por horas y días, temperatura de la estación de AEMET (la más cercana o la que
  elijas) y **avisos** de AEMET.
- **Sin clave de AEMET, todo sale de Open-Meteo** con la misma pantalla.
- **Portugal con el IPMA** (sin clave): previsión, observaciones y avisos; lejos de sus 35
  localidades, Open-Meteo con tu punto exacto (se puede elegir).
- **Una página por ubicación** (la del GPS y las que sigas); el pueblo o el distrito, no sólo el
  municipio, que sale del término municipal en el que estás.
- **Buscador instantáneo** de municipios y pueblos, sin red.
- Aire, polen y UV.
- **Sol y luna** (orto, ocaso, fase, calendario lunar, eventos astronómicos y una fecha a
  elegir) y **mareas del IHM** en la hora del puerto.
- **Netatmo**: tu estación meteorológica (interior, exterior y pluviómetro) en la pestaña
  Actual, con gráficas al tocar cada medida. Usa la sesión de **Calefacción** si la tienes
  instalada; si no, la suya propia. La estación se pide como mucho cada 10 minutos.
- **Cuatro widgets**: Tiempo (2×2, 4×2 y 4×4), Netatmo, Sol·Luna y Mareas, con la ciudad, el
  puerto o el fondo a elegir, y seis escalas de color según la temperatura.
- Español, inglés, gallego, euskera y catalán.

## Compilar

```sh
Tools/instalar-entorno.sh      # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
cat >> local.properties <<'X'  # todo opcional; también se puede poner en Ajustes
aemet.apiKey=…
netatmo.clientId=…
netatmo.clientSecret=…
netatmo.refreshToken=…
X
./gradlew testDebugUnitTest assembleRelease
```

Para iniciar sesión en Netatmo desde Tiempo (sin Calefacción), la app de dev.netatmo.com tiene
que tener la Redirect URI `tiempo-netatmo://oauth-callback`; si no, se pega un refresh token.

Traducciones: `Tools/traducciones.tsv` (la clave es el español) → `python3 Tools/i18n.py`.

## Datos

© AEMET · IPMA · Open-Meteo (CC BY 4.0) · © Instituto Hidrográfico de la Marina · Netatmo ·
pueblos y aldeas de [GeoNames](https://www.geonames.org/) (CC BY 4.0) · términos municipales del
IGN (es-atlas, CC BY 4.0) · contorno de Portugal de Natural Earth (dominio público).

# TiempoDroid — contexto del repositorio

Versión para Android de **Tiempo ES** (AppPersonal, iOS), escrita **desde cero en Kotlin + Jetpack
Compose**. En el móvil se llamará **Tiempo ES**, como en el iPhone. Lo común a las apps de Android
(firma, `versionCode`, secretos, distribución, código compartido) está en el `CLAUDE.md` de la
carpeta de proyectos; aquí va lo propio de esta.

## Estado (21-oct-2026)

**Sin empezar.** Es la más grande de las de Android (unas 11.000 líneas en iOS). Se puede hacer casi
entera en el emulador.

## La especificación es el original

`../AppPersonal` (`github.com/baltamir1978/AEMET-Netatmo-ios`): leer su `README.md` y su
`REVISION.md`. Qué hace:

- **Predicción de AEMET** por horas y días, temperatura observada en la estación más cercana y
  **avisos CAP** de la comunidad autónoma. **Sin clave de AEMET, todo sale de Open-Meteo** con la
  misma interfaz.
- **Portugal con el IPMA** (sin clave): previsión, observaciones y avisos. Allí el selector de
  estación pasa a ser un selector de fuente (la capital del IPMA más próxima u Open-Meteo).
- **Una página por ubicación** (GPS y ciudades que sigas), cada una con su caché en disco.
- **Estación a elegir** por municipio.
- **Sol y luna** (orto, ocaso, crepúsculos, fase, calendario), eventos astronómicos, y **mareas del
  IHM**.
- **Netatmo** opcional: medidas en tiempo real y gráficas al tocar cada tarjeta (ya no hay pestaña
  de gráficas).
- **Nomenclátor** de unos 29.000 núcleos y **buscador instantáneo** sin red.
- **Cuatro widgets**: Tiempo, Netatmo, Sol·Luna y Mareas, configurables (ciudad y fondo) y con
  **fondo según la temperatura** en 6 escalas a elegir en Ajustes (por omisión, «Pocas bandas»).

## Cómo se hace en Android

| En iOS | Aquí |
|---|---|
| WidgetKit + App Group | Glance; los widgets leen la misma caché que la app, en el mismo proceso |
| `WidgetConfigIntent` | Actividad de configuración del widget (ciudad y fondo) |
| `BackgroundTasks` | WorkManager |
| CoreLocation + geocodificador de Apple | `FusedLocationProviderClient` + `Geocoder`; el nomenclátor sigue eligiendo el núcleo |
| Swift Charts | Vico |
| `SunMoonService`, `MoonPhasesService` | Traducir los cálculos o usar una librería (p. ej. `commons-suncalc`) y comprobarla contra la app de iOS |
| OAuth de Netatmo | AppAuth, compartido con CalefaccionDroid |

**Datos que se reutilizan tal cual:** `Nucleos.tsv` y su generador `Tools/build_nomenclator.py`, y
las tablas de las escalas de color (`AppPersonalWidget/WidgetTheme.swift`).

## Trampas que vienen del original

- **AEMET responde en dos pasos**: la primera llamada devuelve una URL de `datos` que caduca. Hay
  que reintentar cuando falla.
- **El IHM publica las mareas en UTC sin decirlo**, y su parámetro `date` elige un día UTC. Pasar
  cada hora a la del puerto y reagrupar por día local pidiendo las dos o tres tablas que solapan;
  sin esto, en verano la pleamar sale dos horas antes.
- **Portugal en mareas**: el IHM sólo sirve Lisboa, que da medio metro de más. Usar el puerto más
  próximo (A Guarda al norte, Ayamonte al sur).
- **Cada sitio en su hora**: la zona horaria sale del territorio (`Atlantic/Canary`, Madeira,
  Azores), también en los widgets.
- **El geocodificador no pasa del municipio** (en todo Llanes devuelve «Llanes»): el nomenclátor
  elige el núcleo más cercano.
- **Los widgets tienen un cupo de recargas**: avisarles agrupado, no en cada página que se pasa (en
  iOS, avisar en cada cambio los dejaba congelados).
- **Escalas de color**: en tramos, sin degradado entre temperaturas; si se mezcla, dentro de una
  banda y en OKLab. Texto azul marino `#0B2236` sobre fondos claros. Bruno eligió las escalas con
  mucho detalle: copiarlas exactas.
- Claves de AEMET y Netatmo en `local.properties`, nunca en el repositorio.

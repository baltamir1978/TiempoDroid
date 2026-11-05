# TiempoDroid — contexto del repositorio

Versión para Android de **Tiempo ES** (AppPersonal, iOS), escrita **desde cero en Kotlin + Jetpack
Compose**. En el móvil se llamará **Tiempo ES**, como en el iPhone. Lo común a las apps de Android
(firma, `versionCode`, secretos, distribución, código compartido) está en el `CLAUDE.md` de la
carpeta de proyectos; aquí va lo propio de esta.

## Estado (5-nov-2026)

**0.2: compila y pasan 22 pruebas; sin probar en el móvil.** APK en `~/Documentos/Tiempo-0.2.apk`.

Hecho, traducido de la app de iOS:
- **Tiempo**: una página por ubicación (GPS y las que sigues), pasando con el dedo, cada una con
  su caché; tarjetas como `AemetCityView`: avisos, la grande en degradado verde (temperatura de
  la estación si es de hace ≤ 2 h, si no la de esta hora), 48 horas, curva de temperatura, 7 días,
  detalles de hoy, estación, aire·polen·UV (Open-Meteo) y fuente.
- **AEMET** en dos pasos, peticiones espaciadas 1,2 s, reintento si 429 y caché en disco a la
  que se vuelve si falla. **Si AEMET no da nada (sin clave, caducada o caída), Open-Meteo**
  adaptado a las mismas estructuras, como en iOS sin clave.
- **Avisos CAP**: el `.tar` de la comunidad, punto en polígono, el nivel más alto por fenómeno,
  sin los ya caducados.
- **Ubicación sin geocodificador** (no hay sin Google Play): `LocationManager`, el municipio de
  AEMET más cercano (`assets/Municipios.tsv`, sacado del maestro de AEMET el 5-nov-2026) y el
  núcleo del nomenclátor (`assets/Nucleos.tsv`, el de iOS). **Buscador instantáneo** sin red.
- **Sol·Luna**: traducción línea a línea de `SunMoonService` (NOAA); fase, iluminación, próximas
  fases. **Mareas del IHM** con la conversión UTC → hora del puerto y reagrupado por día local;
  el puerto más cercano (sin Lisboa) y los dos siguientes.
- **Widget de Tiempo** (pequeño y mediano) con ciudad y fondo por widget, y el **color según la
  temperatura con las seis escalas de iOS copiadas exactas** (`core/TempScale.kt`, con su
  degradado en OKLab y la tinta azul marino `#0B2236`); avisos a los widgets agrupados (3 s);
  refresco cada 3 h con WorkManager.
- **Municipio por término municipal** (0.2): punto en polígono sobre `assets/Recintos.bin` (IGN,
  vía es-atlas 0.6.0; se genera con `Tools/build_recintos.py`); el punto más cercano sólo si no
  cae en ninguno. Con sólo el punto, en San Blas salía Coslada (el de Madrid está en Sol).
  `assets/Distritos.tsv`: los distritos de Madrid, que el nomenclátor no trae.
- **Selector de estación** (tocar la tarjeta del sitio): Automática o una de las 15 más cercanas.
- **Mareas siempre**, con el puerto elegido guardado (Llanes por omisión, como en iOS), y
  **calendario lunar con eventos astronómicos** (`core/Astro.kt`: Meeus 49 y 27, tablas de iOS).
- Arreglado respecto a iOS: la probabilidad de lluvia de la franja «2002» (20 a 02 h, cruza la
  medianoche) no salía nunca; aquí sí.

Falta: **Netatmo** (pestaña Actual, gráficas y su widget), **Portugal con el IPMA**, widgets
Sol·Luna y Mareas, selector de fecha en Sol·Luna, traducciones (en, gl, eu, ca: ahora sólo
español) y probar en el móvil.

**La clave de AEMET de Bruno caducó** (había que renovarla antes del 15-oct-2026): AEMET contesta
401 a todo. Mientras no la renueve en opendata.aemet.es, la app tira de Open-Meteo. Va en
`local.properties` (`aemet.apiKey`), nunca en el repo, o en Ajustes dentro de la app.

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
| CoreLocation + geocodificador de Apple | **`LocationManager`**, no `FusedLocationProviderClient`, que es de Google Play (GrapheneOS). **`Geocoder` tampoco sirve**: sin Google Play no tiene servicio detrás. No hace falta: el nomenclátor y los municipios de AEMET y del IPMA ya van dentro de la app, así que la ubicación se resuelve sin red buscando el núcleo más cercano |
| Swift Charts | Vico |
| `SunMoonService`, `MoonPhasesService` | Traducir los cálculos o usar una librería (p. ej. `commons-suncalc`) y comprobarla contra la app de iOS |
| OAuth de Netatmo | AppAuth, compartido con CalefaccionDroid |

**Datos que se reutilizan tal cual:** `Nucleos.tsv` y su generador `Tools/build_nomenclator.py`, y
las tablas de las escalas de color (`AppPersonalWidget/WidgetTheme.swift`).

## Netatmo va a limitar las consultas (correo del 5-nov-2026)

Desde el 1-mar-2027, límites más bajos para quien consulta periódicamente; Netatmo empuja a los
webhooks, que necesitan un servidor con HTTPS público (el móvil no puede recibirlos). Cuando se
haga la pestaña Actual: pedir la estación como mucho cada 10 min (es lo que tarda en medir) y
compartir la sesión con CalefaccionDroid. Ver el `CLAUDE.md` de CalefaccionDroid.

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

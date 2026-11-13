# TiempoDroid — contexto del repositorio

Versión para Android de **Tiempo ES** (AppPersonal, iOS), escrita **desde cero en Kotlin + Jetpack
Compose**. En el móvil se llamará **Tiempo ES**, como en el iPhone. Lo común a las apps de Android
(firma, `versionCode`, secretos, distribución, código compartido) está en el `CLAUDE.md` de la
carpeta de proyectos; aquí va lo propio de esta.

## Estado (13-nov-2026)

**0.4 (13-nov-2026): pestaña «Embalses».** La reserva de España y por cuencas, tus favoritos, los cinco más cercanos (a la ubicación del GPS o a la primera ciudad que sigues), buscador, ficha de cada embalse (esta semana frente a la misma del año pasado y a la media de 10 años, el año hidrológico y las últimas semanas) y un mapa de OpenStreetMap con los embalses coloreados según su llenado. Embalses.net no tiene API: los datos salen de los servicios públicos de ArcGIS del **Boletín hidrológico de MITECO**, sin clave (`services-eu1.arcgis.com/RvnYk1PBUJ9rrAuT/arcgis/rest/services/Embalses_Mapa`, la semana en curso, y `Embalses_Total`, el histórico semanal desde 1988, con un máximo de 2.000 filas por consulta). El año pasado y la media de 10 años se calculan con la semana más cercana y coinciden con embalses.net. La lógica está en `core/Embalses.kt` (con `EmbalsesTest` y sus dos muestras), la red y la caché en `data/EmbalsesRepo.kt` y las pantallas en `ui/Embalses.kt`. El mapa es `osmdroid` 6.1.20 (dependencia nueva: Bruno prefirió un mapa de OSM a uno dibujado o a uno por cuencas); las teselas de OSM piden nuestro `User-Agent` y la atribución, que está en el mapa y en Ajustes. Textos nuevos en `Tools/traducciones.tsv` (380 claves). Se escribió el 11-nov en el portátil sobre la 0.3.9 y el 13-nov se puso encima de la 0.3.13: la pestaña entra en `AdaptiveNavigation` y sus listas usan `gutter()`. Pasan las 44 pruebas y lint. La release firmada se abrió en el emulador (AVD `prueba`, Android 15): arranca, la pestaña carga la reserva y los embalses más cercanos y el mapa abre con su leyenda, sin caídas; no se pudo ver si las teselas se pintan (sin ventana, las capturas salen en negro). **Confirmada por Bruno en el móvil el 13-nov-2026**: el mapa se ve bien (las teselas de OSM se pintan) y el resto de la pestaña (favoritos, buscador, ficha) «está todo». El modo escritorio, sin probar. APK en `~/Documentos/Tiempo-0.4.apk`.

**0.3.13 (13-nov-2026): aviso de caducidad de la clave de AEMET en la página del tiempo** desde 14 días antes (`aemetKeyWarning` en `data/Data.kt`, en el pie de la página; con sus traducciones). El `state` de OAuth de Netatmo se guarda en disco (`netatmo-oauth-state.bin`) y **se exige a la vuelta del navegador** (antes, si Android mataba la app durante el inicio de sesión, no se comprobaba). El certificado de depuración sólo se acepta como firma de Calefacción en builds de depuración. `app_name` ya no se pide traducido; reglas de copia de seguridad explícitas (`res/xml/data_extraction_rules.xml`). El `Text` propio de `ui/Theme.kt` pasa los argumentos por nombre (Material 3 metió `autoSize` en medio). APK en `~/Documentos/Tiempo-0.3.13.apk`.

**Común a las cinco apps (13-nov-2026):** dependencias al día (Gradle 9.9.1, AGP 9.5.1 con Kotlin integrado —ya no se aplica `kotlin.android`—, Kotlin 2.4.21, Compose BOM 2026.09, `compileSdk` 37, `targetSdk` 36); **código compartido en `../droid-common`** (aquí: `SecureFile` y las piezas de modo escritorio; `ui/Adaptive.kt` se queda con el riel y su `gutter`), que hay que tener clonado al lado (`includeBuild` en `settings.gradle.kts`); **CI** en GitHub Actions (`.github/workflows/android.yml`: pruebas, lint y APK de depuración en cada push); `lintDebug` pasa sin errores y forma parte de la comprobación (`./gradlew testDebugUnitTest lintDebug`). `NonObservableLocale` va como aviso: al cambiar el idioma Android recrea la actividad. **Nada de esto está probado en el móvil.**

**0.3.12: compila y pasan las pruebas. Sin probar en el móvil.** APK en `~/Documentos/Tiempo-0.3.12.apk`.
0.3.12: el widget de la ubicación actual seguía sin cambiar de sitio. Causas: (1) sólo se localizaba al crearse la pestaña Tiempo (`LaunchedEffect(Unit)`): volver a la app ya abierta en segundo plano no localizaba; ahora en cada `ON_RESUME` (`LifecycleResumeEffect`). (2) Con la app cerrada, Android no da la ubicación sin `ACCESS_BACKGROUND_LOCATION`: el `RefreshWorker` caía siempre al sitio guardado. Ahora se pide «Permitir siempre» (diálogo una vez si hay widget de la ubicación actual, y botón en Ajustes → Ubicación). Con él: `HourlyWorker` localiza cada hora (`refreshCurrentPlace`: recarga si cambió el nombre o el dato tiene > 3 h) y el proveedor pasivo (`LocationStore.watch`, PendingIntent → `LocationReceiver` → `CurrentPlaceWorker`) recoge gratis las posiciones que piden otras apps. (3) La estación encontrada al cargar una ubicación actual ya vieja la volvía a guardar como actual (`onStation` en `TiempoApp`): ahora sólo si las coordenadas coinciden.
0.3.11: el widget de la ubicación actual se quedaba en el sitio de antes (San Blas-Canillejas con la app ya en Fuencarral): la página sólo volvía a guardar lo del widget si cambiaba el municipio, y los dos distritos son Madrid. Ahora también si cambia el nombre, y al localizar al abrir la app se guarda siempre (antes sólo al conceder el permiso). Sin probar en el móvil.
0.3.10: **modo escritorio**: en ventana ancha (modo escritorio, tableta) el contenido se centra a 720 dp con el margen como `contentPadding` (la rueda del ratón desplaza también sobre él), y la actividad no se recrea al redimensionar (`configChanges`). Piezas en `ui/Adaptive.kt`; sin probar en modo escritorio de verdad. En ventana ancha, riel a la izquierda en vez de barra inferior (`AdaptiveNavigation`) y flechas para pasar de ciudad (los puntos también se pueden tocar). `DesktopRender` pinta la página de una ciudad a 1280×800 dp como los widgets.
0.3.9: la configuración del widget ya no queda bajo la barra de estado y la cámara (`safeDrawingPadding`: con targetSdk 35 todo va de borde a borde).
0.3.8: en la configuración de cada widget, el interruptor «Color según la temperatura» se guarda al
momento (antes sólo al tocar una ciudad: al volver atrás se perdía) y **opacidad del fondo** (0–100 %,
en los cuatro; `LocalWidgetOpacity`, el degradado y la playa se pintan ya con ese alfa); botón «Listo»
en todos y la ciudad o el puerto actuales marcados.
0.3.7: cabeceras a la misma altura en todas las pestañas (Sol·Luna y Ajustes bajo la barra de estado; las barras de título, de 48 dp en vez de 64) y el calendario lunar sin iconos cortados (el `lineHeight` de 24 sp por omisión no cabía en la celda).
0.3.6: los widgets hacen caso a la ciudad o el puerto elegidos al ponerlos (ver «Trampas de Glance»).
0.3.5: el icono principal del widget de Tiempo, grande y superpuesto en la esquina (no empuja
las horas); Netatmo, Sol·Luna y Mareas rehechos con letra fina grande, anillos e iconos mayores y
menos hueco (Sol·Luna con la duración del día; Mareas con la marea siguiente a la próxima).
0.3.4: la gráfica de temperatura de la app rehecha como la de iOS (curva suave naranja, eje de
grados, lluvia en barras en su franja, horas alineadas con sus puntos: antes se descuadraban
hasta 5 h); widget de Tiempo con letra e iconos más grandes y la lluvia marcada en azul con su
gota (en el grande, barras con su porcentaje bajo la curva).
0.3.3: widgets rehechos con el aire de Breezy Weather: iconos del tiempo dibujados por nosotros
(`widget/WeatherIcons.kt`: sol, luna en su fase, nubes, gotas, copos, rayo, niebla; nada de
emojis), temperatura grande en letra fina, el aviso como etiqueta y en el grande la curva de seis
horas. 0.3.2: selector de estación con las 5 más próximas (la lista de AEMET trae una fila por
estación y hora: hay que deduplicar), «AEMET no disponible: …» en la página si cae a Open-Meteo, y
Ajustes comprueba la clave y dice cuándo caduca (si la de Ajustes caduca antes que la del APK,
manda la del APK).

**Ver los widgets sin móvil:** `./gradlew testDebugUnitTest --tests '*WidgetRender*' --tests '*ChartRender*'
-Dwidgets.out=<carpeta>` los pinta a PNG, y la gráfica de la app (`captureToImage` se cuelga en
Robolectric: se pinta la vista a mano) (Robolectric, Glance → RemoteViews). Las esquinas salen
rectas y las fechas en inglés sólo ahí.

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
- **Ubicación sin geocodificador** (no hay sin Google Play): `LocationManager`, el municipio por
  **término municipal** (punto en polígono sobre `assets/Recintos.bin`, IGN vía es-atlas 0.6.0,
  `Tools/build_recintos.py`; el punto más cercano sólo si no cae en ninguno), el núcleo del
  nomenclátor (`assets/Nucleos.tsv`) y los distritos de Madrid (`assets/Distritos.tsv`).
  **Buscador instantáneo** sin red, con las 35 localidades del IPMA.
- **Portugal con el IPMA** (0.3, `core/Ipma.kt`): previsión (`aggregate`), observación de la
  estación más cercana y avisos, adaptados a las estructuras de AEMET. Un sitio es portugués por
  su código `pt-<globalId>`. Fuente: el IPMA si su localidad está a ≤ 25 km, si no Open-Meteo; se
  cambia tocando la tarjeta del sitio (selector de fuente, como en iOS). Los avisos, siempre del
  IPMA. Para el GPS: si no cae en un término de España y sí en el contorno de Portugal
  (`assets/Portugal.txt`, Natural Earth 10m, `Tools/build_portugal.py`), en Madeira o Azores, o a
  12 km de una localidad del IPMA (la costa de Natural Earth es gruesa).
- **Netatmo** (0.3): **la estación meteorológica** (base interior, módulo exterior `NAModule1` y
  pluviómetro `NAModule3`), no el termostato. Pestaña **Actual** (sólo si está configurado) con
  exterior e interior, pilas de los módulos y avisos del pluviómetro; **gráficas** al tocar cada
  tarjeta (semana, mes, 3 meses, año; la lluvia con `sum_rain` en tiempo real y el pluviómetro
  re-detectado si cambió); **widget** en tres tamaños con anillos (mapas de bits) y fondo verde o
  según la temperatura; refresco cada hora si hay widget. **Sesión compartida con Calefacción**:
  su proveedor `es.altamirano.calefaccion.netatmo` da un `access_token` válido sólo a Tiempo
  firmada con su clave, y Tiempo comprueba la firma de Calefacción (`SharedSession`). Si no, sesión
  propia: credenciales en Ajustes y refresh token pegado (como iOS) o inicio de sesión en el
  navegador con `tiempo-netatmo://oauth-callback` (hay que darla de alta en dev.netatmo.com).
  Cifrada con una clave del Keystore. **La estación se pide como mucho cada 10 min** (caché en
  disco común a la app y al widget), por los límites de Netatmo desde marzo de 2027.
- **Sol·Luna**: traducción línea a línea de `SunMoonService` (NOAA); fase, iluminación, próximas
  fases, **selector de fecha** (0.3), calendario lunar con eventos astronómicos (`core/Astro.kt`).
  **Mareas del IHM** con la conversión UTC → hora del puerto; el puerto elegido guardado (Llanes
  por omisión).
- **Cuatro widgets** (Tiempo, Netatmo, Sol·Luna, Mareas). El de Tiempo, en los tres tamaños de
  iOS (0.3.1): 2×2; 4×2 (por omisión al ponerlo) con sitio y aviso, temperatura, máx/mín, humedad,
  viento y cinco horas con su lluvia; 4×4 con seis horas y cinco días con barra de la semana. Todos cada uno con su configuración (ciudad,
  fondo o puerto) y abriendo su pestaña (`tiempo://aemet|netatmo|cosmos|tides`). Fondo según la
  temperatura con **las seis escalas de iOS copiadas exactas** (`core/TempScale.kt`), ahora con el
  degradado de verdad (mapa de bits estirado). Mareas: la playa con el agua a la altura de la marea.
  Avisos a los widgets agrupados (3 s); tiempo cada 3 h y Netatmo/Sol·Luna/Mareas cada hora.
- **Idiomas** (0.3): es, en, gl, eu, ca. Como en iOS, **la clave es el texto en español**:
  `Tools/traducciones.tsv` (las de iOS copiadas y el resto nuevas) → `python3 Tools/i18n.py` →
  `assets/i18n/<idioma>.tsv`. Se traduce al pintar: los `Text` de `ui` y `widget` son nuestros
  (`ui/Theme.kt`, `widget/GlanceText.kt`) y pasan por `core/I18n.kt`, con huecos `{}` (o `{1}`,
  `{2}` para cambiar el orden) y traduciendo también lo que cae en el hueco. Así salen traducidos
  hasta los cielos que manda AEMET. **Texto nuevo en pantalla: añadirlo a `traducciones.tsv`.**
  El idioma se puede elegir para la app en Ajustes de Android (`locales_config.xml`).
- Arreglado respecto a iOS: la probabilidad de lluvia de la franja «2002» (20 a 02 h, cruza la
  medianoche) no salía nunca; aquí sí.

Falta: probar en el móvil (todo, y la sesión compartida con Calefacción con una cuenta de verdad)).

**Clave de AEMET renovada el 6-nov-2026; caduca el 14-feb-2027** (la fecha va en el `exp` del JWT).
Cuando caduca, AEMET contesta 401 a todo y la app tira de Open-Meteo. Va en
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
webhooks, que necesitan un servidor con HTTPS público (el móvil no puede recibirlos). Hecho: la
estación se pide como mucho cada 10 min (es lo que tarda en medir), las gráficas se guardan
10 min (semana) o 1 h, el widget refresca cada hora y la sesión se comparte con CalefaccionDroid.
Si aun así no basta, la salida es un servidor en casa con los webhooks. Ver el `CLAUDE.md` de
CalefaccionDroid.

## Trampas de Glance

- **Una `Column` o `Row` no pinta más de 10 hijos**: el resto desaparece sin aviso (así faltaba el
  quinto día del widget grande). Agrupar en columnas anidadas.
- **`provideGlance` sólo corre al abrir la sesión**, y la sesión sigue viva un rato: lo que se lea
  ahí (ciudad, puerto, fondo, datos) se queda viejo aunque se llame a `update()`. Por eso el
  Sol·Luna no hacía caso a la ciudad elegida al ponerlo. Cada widget lee dentro de
  `provideContent` con `remember(widgetStamp())`, y `refreshWidget`/`updateAllWidgets` cambian
  ese sello en el estado de Glance antes de `update()`.
- Un `Paint` reutilizado tras pintar con alfa conserva ese alfa también con `shader`.

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

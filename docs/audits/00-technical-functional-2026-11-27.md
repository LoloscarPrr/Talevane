# TALEVANE — Fase 00: Auditoría técnica y funcional
Fecha: 2026-11-27
Base inspeccionada: main, commit 6e73b452b02b9c2d53302eb3a667730c698e415f; versionName 0.7.12, versionCode 41, app.talevane.reader, minSdk 26, targetSdk 35.
Alcance: revisión estática asistida por IA por especialidades, no certificación humana ni pruebas ejecutadas. No modificar main, firma, base de datos ni comportamiento en fase 00. El diseño aprobado está en docs/design/graphite-gold.md.

## Evidencia y hallazgos
Rutas relativas a app/src/main/java/app/talevane/reader/ salvo indicación.
P0 bloquea distribución, P1 alto impacto funcional/seguridad, P2 mejora posterior. "Riesgo" significa no reproducido dinámicamente.

| ID | Prioridad | Evidencia | Acción y aceptación |
|---|---|---|---|
| A00-01 | P0 | app/build.gradle.kts contiene contraseñas literales de firma; workflow publica APK debug. Certificado de APK instalada no cotejado. | Migrar credenciales a secretos; revisar posible exposición de la clave privada, no rotar a ciegas ni cambiar applicationId; comparar certificados de APK previa y nueva, versionCode creciente y actualización sin pérdida. |
| A00-02 | P0 | .github/workflows/build-talevane-from-zip.yml ejecuta testDebugUnitTest y assembleDebug; publica release en eventos que no sean PR, incluidos dispatch. Consulta de runs asociados a PR y estados del commit vacía; no demuestra ausencia de otros runs. | Obtener ejecución identificada de CI, separar verificación de publicación, no clobber release anterior durante auditoría. |
| A00-03 | P1 | data/BookDao.kt SELECT * en observeAll; data/BookEntity.kt content String. Todos los textos forman parte de la lista de biblioteca. | Proyección de metadatos sin content; comprobar corpus de libros extensos, memoria y progreso. Riesgo CursorWindow en libros muy grandes, no reproducido. |
| A00-04 | P1 | data/RoomBookRepository.kt líneas 14–42: recentImportedBook devuelto por get sin invalidación tras saveProgress/toggleBookmark; service actualiza DAO directamente. | Test de importación, guardar, reabrir, alternar marcador y leer estado actualizado; corregir caché sin perder progreso. |
| A00-05 | P1 | data/Importers.kt: PDF OCR decidido por legibilidad global, getText materializa documento completo, EPUB XML usa factory sin endurecimiento explícito; borrado de EPUB temporal solo en éxito. | Fixtures PDF mixto/escaneado/columnas, EPUB malformado; análisis por página, límites de bytes y entradas, XML sin entidades externas/DTD, finally para temporales, comprobar cancelación. |
| A00-06 | P1 | data/DocxImporter.kt: DOM con medidas XML best-effort, sin límites explícitos de tamaño; BookFileImporter.kt inspecciona ZIP sin cuota visible. | Rechazar expansión excesiva con límites, garantizar XML seguro en Android real y conservar lectura OMML ES/EN. |
| A00-07 | P1 | speech/NarrationService.kt ~935 líneas: TTS, estado, cola, progreso, notificaciones, Room y música; updateProgress lanzado asíncrono; no manejo explícito de AudioFocus o desconexión de auriculares encontrado en archivo. | Pruebas de cola, pause/resume, cambio de libro, bloqueo, llamada/audio focus, desconexión, cierre, persistencia final; separar coordinador y políticas luego. Riesgo de orden de escrituras, no reproducido. |
| A00-08 | P1 | audio/AmbientSoundEngine.kt mantiene enrutamiento MIDI/orquesta y mood; NarrationService.kt actualiza mood y ambiente. | Sustituir por controlador opcional de pista relajante, sin mood ni generación; volumen independiente, silencio por defecto propuesto, sin reproducir al pausar; grabaciones con licencias verificadas. Retirar packs solo tras validar migración/limpieza. |
| A00-09 | P1 | application/library/BookLibrary.kt importa Uri y BookEntity; application/reader/ReaderUseCases.kt usa BookEntity; runCatching en preparación/análisis puede capturar cancelación. | Separar modelos de dominio, adaptadores Android y persistencia progresivamente; propagar CancellationException y probar cancelación. |
| A00-10 | P1 | AndroidManifest.xml allowBackup=true; revisar reglas efectivas de backup y contenido de libros. Room exportSchema=false. | Definir política de respaldo/privacidad y exportar esquema; pruebas de migración antes de cambiarlo. No borrar biblioteca. |
| A00-11 | P2 | ui/BookFlowTheme.kt centraliza colores; manifest label BookFlow; README declara 0.7.11. MainActivity pide notificaciones al inicio. | Aplicar Graphite & Gold en fase 05; verificar contraste, escala de fuente, accesibilidad, permisos contextuales, nombre visible y docs. |
| A00-12 | P2 | ai/BookSemanticIndex.kt y pruebas de indexación; Bookflow rama audit/phase-1-1-safe-foundation tiene OfflineNeuralVoiceRenderer.kt (Piper/Sherpa), no prueba de rendimiento en Talevane. | No incorporar IA antes de baseline; comparar calidad y latencia con TTS del sistema, privacidad, licencias, CPU y batería. |

## Fortalezas protegidas
Room multilibro; importación PDF/EPUB/TXT/DOCX; páginas PDF reales y OCR; OMML; idioma ES/EN; texto canónico y offsets; marcador, progreso, selección de texto y lectura desde página; cola TTS y controles de notificación; UI Compose; fronteras BookLibrary, ReaderUseCases, NarrationGateway; tests de páginas, disposición PDF, formatos, OMML, idiomas, normalización TTS, mood, catálogo musical e índice semántico. Existencia en código no equivale a funcionamiento certificado.
BookFlow posterior: conservar aprendizajes Media3, diagnóstico y motor neuronal local; no fusionar proyectos ni perder biblioteca.

## Fase 01 y 02: matriz de regresión
- R01: biblioteca multilibro, persistencia tras reiniciar, marcador y progreso actualizado inmediatamente.
- R02: PDF textual 170 páginas (contador y selección exactos), PDF escaneado, mixto, columnas, EPUB, TXT, DOCX y OMML ES/EN; archivos corruptos y cancelación sin residuos.
- R03: texto canónico inalterado por corrección de voz; offsets válidos, nombres, acentos, puntuación y saltos de párrafo.
- R04: play, pause, resume, stop, seek, cambiar página/libro, notificación, bloqueo, audio focus y auriculares; ninguna cola duplicada; 60 minutos sin interrupciones imputables a la app.
- R05: música opcional, una pista estable, sin dependencia del género, sin sonidos digitales ni audio tras detener; volumen independiente y control de silencio. Pruebas del sistema actual antes de reemplazar.
- R06: inicio, importación, consumo RAM, almacenamiento y batería con corpus idéntico; medir p50/p95 y comparar con baseline en el mismo Redmi 12 5G. Metas numéricas se fijarán después de medir, no inventar métricas actuales.
- R07: actualizar APK sobre instalación previa con mismo package y firma, conservar libros y progreso; validar versión, instalación y funcionamiento.
- R08: UI compacta, landscape, tamaños de fuente ampliados, contraste y navegación accesible.
- R09: seguridad: ZIP/XML malformado, límites, temporales, permisos y privacidad de diagnósticos; verificar ausencia de secretos nuevos.
- R10: evaluar narradores con texto español e inglés, naturalidad, pronunciación, velocidad y latencia; sin comprometer reproducción offline.

## Puertas de salida
F00: inventario estático y riesgos documentados; ejecución baseline pendiente.
F01: comparación histórica y contrato funcional aceptado.
F02: tests unitarios, instrumentados y CI verificable; registrar APK base y certificado antes de tocar lógica.
F03: refactor incremental por límites, sin regresión.
F04: perfiles medidos en dispositivo; solucionar cuellos de botella, sin umbrales inventados.
F05: aplicar tema y validar usabilidad.
F06: release firmada, pruebas y APK verificable.

## Estado
No compilado ni ejecutado; no se verificó certificado, calidad acústica, tiempos, memoria, batería ni instalación. No se modificó código funcional. Siguiente acción técnica: workflow de verificación de rama sin publicar releases, obtener baseline test/build y registrar resultados. Los ensayos en el teléfono se realizan cuando exista APK verificable. El usuario aprobó música relajante opcional y Graphite & Gold; silencio por defecto y orden específico de refactor son propuestas.

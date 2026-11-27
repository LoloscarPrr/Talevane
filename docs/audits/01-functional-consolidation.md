# Fase 01 — Consolidación funcional
Fecha: 2026-11-27. Base candidata Talevane 0.7.12, main 6e73b452b02b9c2d53302eb3a667730c698e415f. Prevalece ADR-001: sin IA neuronal propia, tokens, APIs generativas ni cuentas obligatorias.

## Evidencia histórica
- Talevane release/v0.6.5.2 README: Room multilibro, importación EPUB/PDF/TXT, narración local, controles de bloqueo, capítulos, normalización sin alterar texto canónico, preparación fuera de hilo UI.
- release/v0.6.9.1 app/build.gradle.kts: app.talevane.reader, versionCode 22; identidad mantenida en 0.7.12 (41). Firma de APK previa no cotejada.
- feat/v0.7.0-bookflow-reader README e historial 93095b2: lectura paginada, selección de posición, resaltado condicionado a timings del TTS. Historial posterior: 7076132 firma persistente, fbdefb5 DOCX/OMML, a95b4e4 ES/EN, ab03c57 páginas PDF reales, 7dc1861 buffer continuo.
- Bookflow audit/phase-1-1-safe-foundation: BookSessionStore guarda metadatos de un último libro y offsets por URI; DocumentTextLoader materializa PDF en bytes y busca posición por coincidencia textual; CrashReporter sanitiza mensajes antes de envío; MainActivity concentra UI. No fusionar ni importar motor neuronal.
- Talevane speech/NarratorFoundation.kt: NEURAL_ENABLED=false e interfaces futuras, sin implementación remota en ese archivo. VoiceLabActivity.kt etiqueta voces de red y locales; NarrationService.kt ordena candidatos locales primero, pero no se ha demostrado exclusión de red para todos los caminos. engine.speak(..., null, ...) carece de parámetros de volumen en ese punto; no hay control independiente de volumen de voz verificado. Esto es trabajo funcional futuro, no regresión de algo ya certificado.

## Contrato de producto
Conservar Room, libros, progreso, marcadores, PDF/EPUB/DOCX/TXT, OCR local, OMML, selección de página, offsets canónicos, resaltado si engine aporta timing, navegación por capítulos, abrir con, ES/EN, controles de notificación y fondo.
Corregir caché desactualizada, memoria y parser seguro, cancelación, audio focus, continuidad y pausas. Garantizar elección offline válida en selección, preview y playback; ante ausencia de voz local mostrar instrucciones sin hacer fallback de red automático.
Añadir/validar velocidad y volumen independiente de voz; proponer volumen medio igual a volumen actual de referencia, sujeto a calibración del motor. Música relajante opcional, una pista, volumen independiente, sin análisis del libro, sin MIDI generativo, sin packs orquestales; propuesta silencio por defecto. Licencias verificables.
Eliminar después de cobertura: NarratorFoundation e interfaces neuronales sin uso, composición MIDI, packs y UI de orquesta, mood acoplado al audio. Revisar referencias antes de borrar. OCR no se retira; indexación semántica no se amplía y se revisará uso antes de retirar. Conservar preferencias y datos migrables. No integrar BookFlow neural ni APIs, cuentas, cuotas. Diagnóstico local por defecto; cualquier telemetría externa requiere decisión separada. Media3 no es requisito; solo evaluar si hay beneficio medido.

## Fase 02 — Plan ejecutable y gates
1. CI de rama sin publicar releases, ejecutar testDebugUnitTest y assembleDebug con firma de prueba aislada si no se puede validar la persistente. Etiquetar APK de prueba, nunca sustituir release estable ni prometer actualización con firma distinta.
2. Inventariar resultados de nueve grupos de tests existentes. Añadir pruebas de regresión priorizadas: repositorio/Room (guardar progreso, marcador, reabrir; reproducir caché obsoleta), offsets y páginas, importadores y documentos corruptos, normalización ES/EN, cola y control de narración, preferencia offline, audio independiente.
3. Instrumentación: PDF textual/mixto/escaneado, EPUB, TXT, DOCX OMML, abrir con; rotación, bloqueo, audio focus, reinicio, persistencia; 60 minutos de escucha y modo avión con voz instalada. Medir baseline en mismo dispositivo antes de objetivos de rendimiento.
4. Separar firma y publicación de CI; validar APK previa y nueva mediante apksigner, package y versionCode. No cambiar identidad ni esquema sin pruebas de migración.
5. Fase 03 comienza tras registrar evidencia de tests y baseline; fallos conocidos deben quedar como defectos reproducibles, no ocultarse. Los tests específicos de música adaptativa se reemplazarán cuando cambie explícitamente el contrato a música relajante.

## Estado
F01 comparación y contrato documentados. F00 revisión estática registrada, baseline dinámica pendiente. F02 aún no ejecutada. Sin cambios funcionales, sin APK, main intacta. La selección de versión base es una decisión técnica candidata, no certificación. No se ha verificado el funcionamiento real de versiones históricas.

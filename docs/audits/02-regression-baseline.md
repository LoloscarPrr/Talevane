# Fase 02 — Baseline de regresión
Fecha: 2026-11-27. Aplicación base 0.7.12. No cambios de comportamiento.

## Nuevas pruebas (11 métodos)
- ReaderRegressionTest (5): 170 páginas sintéticas no vacías con separadores canónicos; texto refluible sin pérdida, reanudación por párrafo, posición de narración activa, límites.
- SpeechOffsetRegressionTest (3): mapa de offsets tras correcciones OCR/ortográficas, acentos y puntuación ES/EN, términos de metadatos protegidos.
- LibraryRegressionTest (2 instrumentadas): Room en memoria, aislamiento entre libros; importación TXT, progreso y marcador tras escrituras (A00-04, caché).
- PdfPageNumberRegressionTest (1 instrumentada): conservar página física vacía (A00-05).

Las dos pruebas de comportamiento deseado A00-04 y A00-05 pueden fallar en la base auditada: no están ignoradas ni marcadas como aprobadas. El test de 170 páginas usa texto sintético, no un PDF real. La prueba de caché requiere que la importación TXT local se complete; diagnosticar cualquier fallo de fixture antes de atribuirlo al defecto.

## CI aislado
Workflow .github/workflows/audit-verification.yml: push a audit/00-technical-functional cuando cambian app/Gradle/workflow; permisos de lectura, sin restaurar firma persistente, sin publicar releases ni APK. Gradle 8.11.1/JDK17/SDK35 como flujo existente. Ejecuta tests JVM, ensambla APK debug y APK de instrumentación, verifica metadata/certificado debug, conserva informes. Job separado usa emulador API30 y ejecuta connectedDebugAndroidTest si la compilación base pasa.
No ejecutar el workflow histórico de publicación para esta auditoría. El certificado temporal no es válido para verificar actualización de una instalación anterior. Workflow nuevo solo en rama: ejecución manual puede requerir que exista en rama por defecto; push es disparador previsto. No modificar main para activar CI.
 
## Criterios restantes
- Resultados reales de CI y del emulador, revisar causas de fallos. Corregir después de reproducir, sin borrar pruebas.
- Fixtures documentales reales PDF con texto, OCR, mixto, columnas, EPUB, TXT, DOCX OMML ES/EN y corruptos; pruebas de cancelación y seguridad.
- Pruebas TTS offline, notificaciones, audio focus, cola, volumen, pausas, 60 minutos de reproducción, medidas de RAM/CPU/batería en dispositivo real.
- Comparar certificado de APK anterior con futura APK firmada; preservar biblioteca/progreso. Sin IA neuronal ni red obligatoria conforme ADR-001.
- Mantener música existente hasta proteger narración; posteriormente sustituir por música relajante opcional según contrato actualizado.

## Estado
Código de pruebas y configuración CI incorporados; no se ha verificado compilación, ejecución ni resultados. No hay APK entregable. Las pruebas Android son independientes de libros reales. Fase 02 permanece abierta hasta evidencia de ejecución y cobertura restante. Los archivos de producción Kotlin y main no fueron modificados.

# ADR-001 — Lector local sin IA neuronal ni créditos

Fecha: 2026-11-27. Estado: decisión de producto del titular.

## Decisión
No integrar motores de narración neuronal propios (incluidos Piper/Sherpa de BookFlow), APIs generativas, tokens, créditos, suscripciones obligatorias ni modelos neuronales descargables como dependencia de Talevane. Los modelos locales de BookFlow no requieren créditos pero tienen costes de recursos; tampoco se incorporarán.

Conservar Android TextToSpeech, priorizando voces disponibles sin conexión y sin coste por uso para Talevane. Mejorar segmentación, pausas, normalización conservadora, velocidad, volumen, cola y reproducción de fondo. La calidad depende del motor instalado. No prometer voces humanas ni funcionamiento de voces no instaladas. Conservar OCR y soporte documental, incluida matemática OMML; la exclusión se refiere a IA generativa/narración neuronal propia y no obliga a retirar el OCR local. No enviar texto a proveedores externos como parte del producto. No integrar asistente generativo ni ampliar el índice semántico en este ciclo; revisar código no utilizado después de pruebas de regresión.

Conservar biblioteca, progreso, marcadores, lectura desde página, español/inglés, Graphite & Gold y música instrumental relajante opcional. Retirar música adaptativa tras proteger el comportamiento de narración. Sin backend obligatorio.

## Impacto sobre planes anteriores
Esta decisión prevalece sobre cualquier propuesta de evaluación o integración neuronal en docs/audits/00-technical-functional-2026-11-27.md (A00-12, R10 y fases posteriores). R10 pasa a evaluar únicamente voces TTS del dispositivo, modo sin conexión, pronunciación, pausas y latencia. Media3 y diagnóstico son opciones técnicas independientes, no requisitos. La eliminación física de código no utilizado se hará después de la fase de pruebas, sin alterar funcionalidades protegidas.

## Validación
En modo avión con voz compatible instalada, importar y leer archivos locales, narrar en español e inglés, pausar, reanudar y conservar progreso sin cuentas, cuotas ni créditos. No realizar llamadas externas para narración en este modo. Las siete fases de recuperación permanecen vigentes.

Esta ADR no modifica código funcional, main, firma, APK ni esquema de datos.

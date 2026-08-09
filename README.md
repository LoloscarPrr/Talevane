# Talevane v0.6.4.1

Offline-first Android book reader evolving into an immersive listening experience.

## v0.6.4.1 — Long-book reader performance
- Replaces the single giant Compose `Text` with a virtualized `LazyColumn` reader
- Long books are split into reading chunks and only visible/nearby chunks are composed
- Book structure analysis and reading-chunk preparation run on a background dispatcher
- Fresh imports are handed directly from the repository cache instead of immediately re-reading the whole book from Room
- Opening/preparation screens now expose explicit loading or error states instead of an unexplained spinner
- Chapter jumps, narration-follow scrolling and progress persistence are mapped to canonical character positions through the chunks
- Original book text remains unchanged

## v0.6.4 — Book Structure & Fast Import
- Book import now runs on an I/O dispatcher instead of blocking the Compose UI
- Immediate import progress dialog after choosing a file
- New local book-structure analyzer separates likely front matter from reading content
- Chapter detection recognizes explicit chapter/part/section headings, Roman numerals, numbered headings and isolated title-like headings
- Likely table-of-contents duplicates are de-duplicated in favor of later real section occurrences
- Narration started before the detected reading start automatically jumps to the first real reading section instead of reading publisher/edition metadata
- Chapter navigation hides likely front-matter metadata and exposes more useful sections
- Original extracted book text remains untouched; structure is stored only as positions into the canonical text

## Existing foundation
- Import EPUB, PDF and TXT
- Local Room library and offline reading
- Background TextToSpeech narration
- Lock-screen and notification media controls
- Chapter / section navigation
- Mood Engine based on nearby reading context
- Procedural adaptive musical soundtrack with independent volume
- Voice Lab with per-book narration voice choice
- Talevane icon and Material 3 dark UI

## Current structure scope
Book structure is detected locally from extracted text. It is heuristic rather than a full PDF semantic parser, so unusually formatted scans may still need future refinement. EPUB-native table-of-contents metadata remains a later improvement.

## Canonical rule
The original book text remains canonical. Context systems may analyze reading position, structure and tone, but must never rewrite, summarize over or silently replace the source text.

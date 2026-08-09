# Talevane v0.6.4

Offline-first Android book reader evolving into an immersive listening experience.

## v0.6.4 — Book Structure & Fast Import
- Book import now runs on an I/O dispatcher instead of blocking the Compose UI
- Immediate import progress dialog after choosing a file
- New local book-structure analyzer separates likely front matter from reading content
- Chapter detection recognizes explicit chapter/part/section headings, Roman numerals, numbered headings and isolated title-like headings
- Likely table-of-contents duplicates are de-duplicated in favor of later real section occurrences
- Narration started before the detected reading start automatically jumps to the first real reading section instead of reading publisher/edition metadata
- Chapter navigation hides likely front-matter metadata and exposes more useful sections
- Original extracted book text remains untouched; structure is stored only as positions into the canonical text

## v0.6.3 — Musical Soundtrack
- Replaces the v0.6 ambient drone with a procedural musical sequencer
- Mood-specific chord progressions, scales and tempos
- Tonal pad, bass, arpeggios and melodic motifs generated locally
- Soft procedural percussion for tension/action where appropriate
- Noise is reduced to a subtle texture instead of being the main soundtrack
- Eight-second crossfades preserve gradual mood transitions
- Music remains fully offline and original: no external songs or copyrighted samples
- Existing independent soundtrack volume and background playback remain intact

## v0.6.2 — Voice Lab
- Fixes gender-tag matching so `female` can no longer be mistaken for `male`
- Masculine and Feminine modes open a real installed-voice laboratory
- Preview Spanish TTS voices before selecting one
- Selected masculine/feminine voice is stored per book
- Auto mode reuses a saved voice when the inferred author profile matches
- Pitch shifting is now only a clearly-labelled last-resort approximation

## v0.6.1 — Reading Continuity & Voice
- Resume from the beginning of the last paragraph or nearest reliable text block
- Exact progress still persists independently for every book
- Per-book narration preference: Automatic, Masculine, Feminine or System
- Automatic author profile is conservative: when Talevane cannot infer it reliably, it keeps the system voice
- Voice preference persists locally without changing the Room library schema
- Narration applies the selected profile while retaining speed controls and adaptive soundtrack

## Existing foundation
- Import EPUB, PDF and TXT
- Local Room library and offline reading
- Background TextToSpeech narration
- Lock-screen and notification media controls
- Chapter / section navigation
- Mood Engine based on nearby reading context
- Procedural adaptive musical soundtrack with independent volume
- Talevane icon and Material 3 dark UI

## Current structure scope
Book structure in v0.6.4 is detected locally from extracted text. It is heuristic rather than a full PDF semantic parser, so unusually formatted scans may still need future refinement. EPUB-native table-of-contents metadata remains a later improvement.

## Canonical rule
The original book text remains canonical. Context systems may analyze reading position, structure and tone, but must never rewrite, summarize over or silently replace the source text.

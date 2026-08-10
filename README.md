# Talevane v0.6.4.2

Offline-first Android book reader evolving into an immersive listening experience.

## v0.6.4.2 — Natural Narration & Genre Mood
- PDF layout line breaks are normalized only for TTS playback, so the narrator no longer pauses at every visual line
- Real punctuation drives speech pauses and chunk boundaries instead of PDF line wrapping
- Paragraph breaks retain a light pause while canonical text and character positions remain untouched
- Slightly larger TTS chunks reduce unnecessary hand-offs between queued utterances
- Mood Engine uses stronger horror/mystery/tension vocabulary and a weak genre prior from the book opening
- Lovecraft/Cthulhu/horror markers bias ambiguous philosophical passages toward Mystery/Tension instead of Reflexion
- Reflective scoring is more specific and no longer treats generic existence/consciousness vocabulary as sufficient by itself

## v0.6.4.1 — Long-book reader performance
- Replaces the single giant Compose `Text` with a virtualized `LazyColumn` reader
- Long books are split into reading chunks and only visible/nearby chunks are composed
- Book structure analysis and reading-chunk preparation run on a background dispatcher
- Fresh imports are handed directly from the repository cache instead of immediately re-reading the whole book from Room
- Opening/preparation screens now expose explicit loading or error states instead of an unexplained spinner
- Chapter jumps, narration-follow scrolling and progress persistence are mapped to canonical character positions through the chunks
- Original book text remains unchanged

## v0.6.4 — Book Structure & Fast Import
- Book import runs on an I/O dispatcher instead of blocking the Compose UI
- Immediate import progress dialog after choosing a file
- Local book-structure analyzer separates likely front matter from reading content
- Chapter detection recognizes explicit chapter/part/section headings, Roman numerals, numbered headings and isolated title-like headings
- Likely table-of-contents duplicates are de-duplicated in favor of later real section occurrences
- Narration started before the detected reading start automatically jumps to the first real reading section instead of reading publisher/edition metadata

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

## Canonical rule
The original book text remains canonical. TTS preparation, context systems and structure analysis may create temporary derived views, but must never rewrite or replace the stored source text.

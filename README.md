# Talevane v0.6.5

Offline-first Android book reader evolving into an immersive listening experience.

## v0.6.5 — Piano Engine + Narrator Foundation
- Adaptive soundtrack is now piano-only: no pads, percussion or noise layers
- Mood controls piano harmony, register, tempo, note density, decay and dynamics
- Mystery/terror use sparse lower-register piano; tension uses tighter repeating figures; reflective/calm/warm modes use gentler arpeggios and melodies
- Eight-second mood crossfades remain in place
- Voice Lab now ranks installed voices for narration suitability instead of merely listing every TTS voice equally
- Recommended voices consider Android quality/latency metadata, language, gender tags when actually supplied, and neural/natural voice hints
- Voices explicitly tagged as the opposite sex are not selectable for the requested Masculine/Feminine filter
- Unknown voice sex is labelled as unverified instead of guessed
- Network TTS voices are no longer automatically buried below every offline voice, because some engines expose higher-quality voices through network-backed entries
- Automatic author profile now recognizes H. P. Lovecraft as masculine
- Provider-neutral neural narrator interfaces are included as a foundation for future human-sounding online narration
- v0.6.5 does not bundle an API key or send book text to any external service; device TTS remains the active narrator

## v0.6.4.2 — Natural Narration & Genre Mood
- PDF layout line breaks are normalized only for TTS playback, so the narrator no longer pauses at every visual line
- Real punctuation drives speech pauses and chunk boundaries instead of PDF line wrapping
- Paragraph breaks retain a light pause while canonical text and character positions remain untouched
- Mood Engine uses stronger horror/mystery/tension vocabulary and a weak genre prior from the book opening

## v0.6.4.1 — Long-book reader performance
- Virtualized `LazyColumn` reader for long books
- Structure/chunk preparation off the UI thread
- Fresh imports can be handed directly from memory instead of immediately re-reading the full book from Room

## Existing foundation
- Import EPUB, PDF and TXT
- Local Room library and offline reading
- Background TextToSpeech narration
- Lock-screen and notification media controls
- Chapter / section navigation
- Mood Engine based on nearby reading context
- Adaptive procedural piano with independent volume
- Voice Lab with per-book narration voice choice
- Talevane icon and Material 3 dark UI

## Neural narrator scope
The neural narrator layer in v0.6.5 is architecture only. A future release can connect a server-side provider and explicit privacy/consent flow without storing provider secrets inside the APK. The offline Android TTS path remains available as the baseline.

## Canonical rule
The original book text remains canonical. TTS preparation, context systems and structure analysis may create temporary derived views, but must never rewrite or replace the stored source text.

# Talevane v0.6.3

Offline-first Android book reader evolving into an immersive listening experience.

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

## Current voice scope
Android TTS engines do not expose a universal reliable gender label for every installed voice. Talevane therefore uses tagged voices when an engine provides that metadata and otherwise applies a conservative voice profile. The user can always override the automatic choice per book.

## Canonical rule
The original book text remains canonical. Context systems may analyze reading position and tone, but must never rewrite, summarize over or silently replace the source text.

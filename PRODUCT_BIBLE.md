# Talevane Product Bible — current canon

## Promise
Turn books the user already owns into a private, immersive reading and listening experience.

## Non-negotiables
1. Original book text remains canonical.
2. Core reading works offline.
3. Import and reading are not locked behind a subscription.
4. AI enhances; it does not gate access.
5. Mood/music supports the story instead of distracting.
6. Mood transitions are gradual and context-aware.
7. Library stays local by default.

## Implemented milestones
### v0.1 — Reader foundation
- Import TXT/EPUB/PDF.
- Persist imported books locally.
- Open extracted text.
- Persist progress.
- Adjust font size.
- Toggle bookmark.
- Build debug APK in GitHub Actions.

### v0.2 — Reading continuity
- Resume from saved position.
- Live reading percentage.
- Improved library cards and metadata cleanup.

### v0.3 — Identity + foreground narration
- Talevane icon and branded launch screen.
- Android TextToSpeech narration.
- Play / pause and narration speed controls.

### v0.4 — Audiobook foundation
- Narration continues in a foreground media service.
- System notification and lock-screen media controls.
- Background progress persistence.
- First chapter / section detection and navigation.

### v0.5 — Mood Engine
- Local context analysis around the current reading position.
- Stable mood states: Neutral, Calm, Reflective, Melancholy, Tension, Mystery, Action and Warmth.
- Mood intensity surfaced in the reader.
- Continue-reading library card and presentation cleanup.

### v0.6 — Adaptive Soundtrack
- Original procedural soundscapes generated locally; no external tracks required.
- Mood Engine drives background audio while narration progresses.
- Gradual crossfades between mood states.
- Independent persistent soundtrack volume.

### v0.6.1 — Reading continuity + voice profile
- Resume from the beginning of the last paragraph/block while retaining precise progress.
- Per-book Auto / Masculine / Feminine / System narration preference.

### v0.6.2 — Voice Lab
- Masculine/Feminine selection uses actual voices reported by the installed Android TTS engine.
- Users can preview voices before saving one for a book.
- Gender-tag matching uses token boundaries; `female` must never be misclassified as `male`.
- Pitch-only changes are a labelled last-resort approximation, never presented as a confirmed speaker sex.

### v0.6.3 — Musical Soundtrack
- Adaptive audio gained tonal centre, scales, chord progressions, arpeggios and melody.
- Music is generated locally; no external songs or copyrighted samples are bundled.

### v0.6.4 — Book Structure & Fast Import
- File parsing/import runs away from the UI thread and provides immediate loading feedback.
- Local structure analysis distinguishes likely front matter from meaningful reading content.
- Narration started before detected reading content skips publisher/edition metadata.

### v0.6.4.1 — Virtualized long-book reader
- Long source text is virtualized into canonical-position chunks.
- Structure/chunk preparation runs off the UI thread.
- Chapter jumps, progress and narration-follow retain canonical character-position mapping.

### v0.6.4.2 — Natural Narration & Genre Mood
- PDF layout line breaks become spaces only in the temporary TTS view.
- Speech chunking follows punctuation instead of visual line endings.
- Mood detection uses stronger horror/mystery/tension language and a weak whole-book genre prior.

### v0.6.5 — Piano Engine + Narrator Foundation
- Adaptive soundtrack is piano-only: no pads, drums, percussion or noise layers.
- Mood controls piano harmony, register, tempo, density, decay and dynamics.
- Voice Lab ranks installed voices by narration suitability instead of presenting all voices as equally useful.
- Recommended voice scoring considers quality, latency, language, actual gender tags when present and neural/natural metadata hints.
- A voice tagged as the opposite sex cannot be selected under the requested Masculine/Feminine filter.
- Unknown speaker sex is shown as unverified, never guessed from pitch.
- Network-backed system voices may rank above robotic offline voices when their metadata suggests better narration quality.
- H. P. Lovecraft is included in conservative automatic author-profile inference.
- Provider-neutral neural narrator interfaces are now part of the architecture.
- v0.6.5 does not embed provider API keys and does not send source-book text to an external service.

### v0.6.5.1 — Audio balance & narration cadence
- Piano must remain clearly audible beneath speech at normal slider positions.
- PDF layout breaks must never become invented hard punctuation in TTS.
- A selected Android voice is only labelled active after the TTS engine accepts it.
- Prefer genuinely higher-quality/natural system voices when available, while keeping offline fallback.

### v0.6.6 — Book Score + Tap to Narrate
- Soundtrack identity is per-book, not only per-mood. A stable local book seed creates a recurring musical theme that moods reinterpret.
- Book identity must affect composition structure (motif, harmony order, accompaniment, tempo/register), not merely transpose the same piece.
- Reader text is directly addressable: tapping a sentence may start narration from that canonical position.
- Tap-to-narrate must preserve canonical text offsets, progress, chapters and background narration.

## Roadmap
v0.7 Context AI and narration direction
v0.8 Neural narrator integration, voice packs, sentence highlighting and deeper audiobook controls
v0.9 Cover art, richer metadata and library polish

## Audio canon
- Narration remains intelligible above the soundtrack.
- Background music should be supportive, low-distraction and gradual.
- The default adaptive soundtrack instrument is piano; mood changes alter how the piano plays rather than swapping unrelated sound palettes.
- Mystery/terror may use lower register, sparse notes and dissonant harmony; reflection/calm/warmth may use gentler voicings and arpeggios.
- A mood change does not require an immediate hard audio cut.
- The offline baseline must remain usable without external music services.
- Never claim a speaker sex from pitch alone. Prefer a real installed voice selected or explicitly identified by the TTS engine.
- PDF line wrapping is layout, not prose punctuation; narration must not pause simply because a visual line ended.

## Narrator canon
- Device TTS remains the offline fallback even after neural narration exists.
- Human-sounding neural narration must be optional and clearly distinguishable from device TTS.
- Provider secrets must never be embedded directly in the APK.
- Sending source text to an external narrator requires an explicit user-facing privacy flow.
- Neural-provider integration must preserve canonical source offsets so progress, chapters and highlighting stay correct.

## Structure canon
- Front matter may remain visible to the reader, but narration should not treat publisher/edition metadata as the narrative beginning by default.
- Chapter and reading-start detection must be represented as positions into canonical text, never by rewriting the book.
- Heuristic structure detection must fail conservatively when confidence is low.

## Performance canon
- Large books must open without requiring the entire text to be measured or laid out on the UI thread.
- Background preparation must preserve responsiveness and expose a visible loading/error state.

## Canonical rule
Future AI may analyze context for narration, structure, mood and soundtrack systems, but must never rewrite, summarize over, or silently replace the source book text.

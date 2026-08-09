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
- Mood Engine drives the background ambience while narration progresses.
- Gradual crossfades between mood states.
- Independent persistent ambience volume.
- Ambience continues with background narration and lock-screen playback.

### v0.6.1 — Reading continuity + voice profile
- Resume from the beginning of the last paragraph/block while retaining precise progress.
- Per-book Auto / Masculine / Feminine / System narration preference.

### v0.6.2 — Voice Lab
- Masculine/Feminine selection uses actual voices reported by the installed Android TTS engine.
- Users can preview voices before saving one for a book.
- Gender-tag matching uses token boundaries; `female` must never be misclassified as `male`.
- Pitch-only changes are a labelled last-resort approximation, never presented as a confirmed speaker sex.

### v0.6.3 — Musical Soundtrack
- Adaptive audio is explicitly musical rather than primarily drone/noise based.
- Each mood defines a tonal centre, scale, chord progression, tempo, arpeggio and melodic motif.
- Bass and selective procedural percussion add musical structure without overpowering narration.
- Music is generated locally from synthesis; no external songs or copyrighted samples are bundled.

### v0.6.4 — Book Structure & Fast Import
- File parsing/import runs away from the UI thread and provides immediate loading feedback.
- Local structure analysis distinguishes likely front matter from the beginning of meaningful reading content.
- Chapter/section detection supports explicit headings, Roman numerals, numbered headings and title-like isolated headings.
- Likely table-of-contents duplicates are de-duplicated toward later real section occurrences.
- Narration started before the detected reading start jumps to the first reading section instead of reciting publisher/edition metadata.
- Structure analysis never edits, trims or replaces the canonical extracted source text.

### v0.6.4.1 — Virtualized long-book reader
- Long source text must not be laid out as one giant UI text node.
- Reader content is virtualized into canonical-position chunks so only visible/nearby text is composed.
- Structure/chunk preparation runs off the UI thread.
- Freshly imported books may be handed directly from memory to avoid an unnecessary immediate full database read.
- Chapter jumps, progress and narration-follow behavior retain canonical character-position mapping.

## Roadmap
v0.7 Context AI and narration direction
v0.8 Voice packs, sentence highlighting and deeper audiobook controls
v0.9 Cover art, richer metadata and library polish

## Audio canon
- Narration remains intelligible above the soundtrack.
- Background music should be supportive, low-distraction and gradual.
- A mood change does not require an immediate hard audio cut.
- The offline baseline must provide actual tonal/musical structure, not depend primarily on noise or a continuous drone.
- Future richer soundtrack sources must preserve a usable offline path.
- Never claim a speaker sex from pitch alone. Prefer a real installed voice selected or explicitly identified by the TTS engine.

## Structure canon
- Front matter may remain visible to the reader, but narration should not treat publisher/edition metadata as the narrative beginning by default.
- Chapter and reading-start detection must be represented as positions into canonical text, never by rewriting the book.
- Heuristic structure detection must fail conservatively when confidence is low.

## Performance canon
- Large books must open without requiring the entire text to be measured or laid out on the UI thread.
- Background preparation must preserve responsiveness and expose a visible loading/error state.

## Canonical rule
Future AI may analyze context for narration, structure, mood and soundtrack systems, but must never rewrite, summarize over, or silently replace the source book text.

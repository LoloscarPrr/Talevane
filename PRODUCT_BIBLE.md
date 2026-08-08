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

## Roadmap
v0.7 Context AI and narration direction
v0.8 Voice packs, sentence highlighting and deeper audiobook controls
v0.9 Cover art, richer metadata and library polish

## Audio canon
- Narration remains intelligible above ambience.
- Background sound should be supportive, low-distraction and gradual.
- A mood change does not require an immediate hard audio cut.
- Offline procedural ambience is the baseline; future richer soundtrack sources must preserve a usable offline path.
- Never claim a speaker sex from pitch alone. Prefer a real installed voice selected or explicitly identified by the TTS engine.

## Canonical rule
Future AI may analyze context for narration, mood and soundtrack systems, but must never rewrite, summarize over, or silently replace the source book text.

# Talevane

**Talevane** is an offline-first Android reading app designed to turn books into a more immersive experience.

The long-term vision is to combine reading, narration, AI context analysis, adaptive soundtrack, and mood-aware ambience without changing the original text of the book.

## v0.1

This first version focuses on building a stable reading core.

### Current features

- Import EPUB files
- Import PDF files
- Import TXT files
- Store books locally
- Extract title and author when available
- Open books in a native reader
- Persist reading progress
- Resume from the last reading position
- Adjustable font size
- Bookmarks
- Dark Material 3 interface
- Offline-first architecture

## Product principles

Talevane follows a few non-negotiable rules:

1. The original book text is canonical.
2. AI may analyze context, mood, pacing and meaning, but must never silently rewrite the source text.
3. Core reading must work offline.
4. A user must be able to open and read their own books without requiring a subscription.
5. AI should enhance the experience, not become a dependency for basic reading.
6. Future music and ambience should support the story instead of distracting from it.
7. User libraries should remain local by default.

## Planned architecture

Talevane is being structured around independent modules:

- `Library`
- `Reader`
- `Narration`
- `MoodEngine`
- `Soundtrack`
- `AI`

This allows the reading core to remain stable while new immersive features are added later.

## Roadmap

### v0.1 — Reading Core
- Library
- EPUB / PDF / TXT import
- Reader
- Reading progress
- Bookmarks
- Basic reading preferences

### v0.2 — Narration
- Text-to-speech
- Background playback
- Lock-screen controls
- Playback speed
- Resume audio position

### v0.3 — Reading Structure
- Robust chapter detection
- Chapter navigation
- Better reading-position tracking
- Improved metadata and cover support

### v0.4 — Mood Engine
- Detect story mood
- Mood intensity
- Gradual mood transitions
- Mixed emotional states

Examples:

- Calm
- Happy
- Sad
- Mystery
- Tension
- Fear
- Hope
- Melancholy
- Discovery
- Action
- Epic

### v0.5 — Adaptive Soundtrack
- Original modular soundtrack system
- Music layers that react to story context
- Smooth transitions
- Independent narration / music / ambience volume
- Offline soundtrack engine where possible

### v0.6 — Context AI
- Scene-context analysis
- Mood prediction
- Narrative pacing awareness
- Intelligent soundtrack decisions
- Narration assistance
- Optional contextual summaries and tools

## Adaptive soundtrack vision

The goal is not to simply attach one song to each chapter.

Talevane should eventually understand the emotional progression of a scene and adapt the soundtrack gradually.

For example:

A peaceful scene may begin with soft piano.

If the story becomes mysterious, subtle low textures can enter.

If tension increases, sustained strings and rhythmic elements can gradually appear.

When the danger passes, those layers should fade naturally instead of switching tracks abruptly.

The soundtrack engine should behave more like an adaptive film score than a playlist.

## Technology

Talevane is being developed as a native Android application using:

- Kotlin
- Jetpack Compose
- Material 3
- Room
- DataStore
- GitHub Actions

Future versions are expected to use Android media APIs / Media3 for narration and background playback.

## Build

The project includes a GitHub Actions workflow that builds a debug APK.

The resulting APK can be found in the workflow artifacts after a successful build.

## Status

**Current version:** `v0.1`

Talevane is currently in active development.

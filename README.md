# Talevane v0.6

Offline-first Android book reader evolving into an immersive listening experience.

## v0.6 — Adaptive Soundtrack
- Procedural ambient soundscapes generated locally inside the app
- Mood-driven sound profiles for Neutral, Calm, Reflective, Melancholy, Tension, Mystery, Action and Warmth
- Gradual crossfades when the Mood Engine changes state
- Ambient audio continues with narration while the screen is locked
- Independent ambience volume from 0% to 100%
- Ambience volume persists between sessions
- Reader UI explicitly shows when ambience is actually playing
- No external music files, streaming service or network connection required

## Existing foundation
- Import EPUB, PDF and TXT
- Local Room library and offline reading
- Resume reading and save progress
- Background TextToSpeech narration
- Lock-screen and notification media controls
- Chapter / section navigation
- Mood Engine based on nearby reading context
- Talevane icon and Material 3 dark UI

## Current audio scope
v0.6 uses the Android TextToSpeech engine for narration and an original procedural ambience engine for background sound. It does not yet use recorded orchestral tracks, character voice packs or AI narration direction.

## Canonical rule
The original book text remains canonical. Context systems may analyze reading position and tone, but must never rewrite, summarize over or silently replace the source text.

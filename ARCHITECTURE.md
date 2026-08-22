# BookFlow architecture

BookFlow stays native Android (Kotlin + Jetpack Compose), but follows the same maintainability principle used in WeekFlow: platform code, orchestration, UI, and persistence must have explicit boundaries.

## Layers

### `platform/`
Android-only adapters. Intents, runtime permissions, content providers and other OS integration belong here. Platform code translates Android events into simple values/commands and should not contain reading or business rules.

### `presentation/`
ViewModels and UI state. Presentation owns screen/app state and coordinates user-visible workflows. Compose screens render state and emit actions; they should not perform persistence or Android intent parsing themselves.

### `ui/`
Compose rendering and interaction. Keep composables focused on layout, transient visual state and callbacks. Long-running workflows belong in presentation/application.

### `application/`
Stable capabilities and use-case contracts such as `BookLibrary`, narration orchestration and reader preparation. Avoid Android framework types in these contracts whenever practical.

### `data/`
Room, file importers, format parsing and concrete implementations of application contracts. Android storage/content resolution is allowed here because it is an implementation detail.

### `reading/`, `chapters/`, `language/`, `mood/`, `library/`
Domain-oriented engines and presentation-independent transformations. Prefer pure Kotlin here.

### `speech/` and `audio/`
Android media/TTS implementations. They may depend on Android, but UI must reach them through application/presentation boundaries rather than direct calls.

## Dependency direction

`platform -> presentation -> application <- data`

`ui -> presentation/application`

Domain-oriented packages should avoid depending on `ui`, activities or Room.

## Rules for future changes

1. `MainActivity` remains a thin Android entry point.
2. New app-wide workflows get a ViewModel/state owner instead of growing `TalevaneRoot`.
3. Application contracts should not expose `Activity`, `Intent`, `Context` or `android.net.Uri` unless there is no reasonable abstraction.
4. Compose must not register broadcasts, parse external intents or call DAOs.
5. Data implementations may use Android/Room internally but expose application contracts.
6. New features should be grouped by responsibility, not appended to giant central files.
7. Refactors should preserve canonical book text and existing reading/narration behavior unless a feature explicitly changes it.

This document is the architectural contract for future BookFlow work.

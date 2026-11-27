# Talevane — Graphite & Gold

Approved visual direction, 2026-11-27. This specification is not an implementation or a validated build.

## Product direction
Reading and natural narration first. Remove context-driven soundtrack generation and layered orchestration after regression coverage; retain optional relaxing instrumental recordings, independent volume, silence option, and smooth transitions. Do not alter narration, library, document import, canonical text, or progress as part of visual work.

## Palette
| Token | Hex | Role |
|---|---|---|
| Background | #101114 | Deep charcoal |
| Panel | #1B1D22 | Polished graphite |
| Reader surface | #282B31 | Reading surface |
| Accent | #D4B46E | Soft gold |
| Border | #806A43 | Aged gold |
| Text | #F2EEE5 | Ivory |
| Muted | #AFADB0 | Warm silver |

Use subtle highlights or tonal gradients on decorative panels only; no heavy textures behind reading text. Gold accents and thin 1dp borders, not saturated yellow or glowing effects. Keep reader text high contrast; verify WCAG AA contrast for text and meaningful controls (muted borders may be decorative only). Buttons and touch targets at least 48dp. Preserve font scaling, accessibility semantics and existing navigation.

## Screens
- Library: existing book cards, progress, import and resume actions; gold accents, charcoal panels.
- Reader: distraction-free ivory text, graphite surface, gold focus and selected states; preserve page boundaries and position offsets.
- Audio controls: clearly labeled independent speech speed, voice volume and optional ambient volume; keep play/pause and resume.
- Settings: consistent surface hierarchy; distinguish active states with text/icon in addition to color.

## Implementation sequence
1. Baseline audit and verified test/build results before UI changes.
2. Add regression coverage for existing behavior.
3. During UI phase, extend existing `app/src/main/java/app/talevane/reader/ui/BookFlowTheme.kt` and migrate hard-coded colors incrementally. Do not rename internal symbols just for branding.
4. Validate on compact Android phone, large font, portrait/landscape and dark theme. Verify reader contrast and navigation. No changes to applicationId, database schema, signing, version or main in this design-only phase.

Navigation layout sketches are illustrative, not approved functional additions. Relaxing audio tracks and licensing must be verified before inclusion.

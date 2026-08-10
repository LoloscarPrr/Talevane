from pathlib import Path

# Connect the new context-backed MIDI piano engine and version the hotfix.
p = Path('app/src/main/java/app/talevane/reader/speech/NarrationService.kt')
s = p.read_text()
old = 'ambientSound = AmbientSoundEngine().apply { setVolume(ambientVolume) }'
new = 'ambientSound = AmbientSoundEngine(applicationContext).apply { setVolume(ambientVolume) }'
if old not in s:
    raise SystemExit('AmbientSoundEngine constructor marker not found')
p.write_text(s.replace(old, new, 1))

p = Path('app/build.gradle.kts')
s = p.read_text()
if 'versionCode = 14' not in s or 'versionName = "0.6.5.1"' not in s:
    raise SystemExit('Expected v0.6.5.1 version marker not found')
s = s.replace('versionCode = 14', 'versionCode = 15', 1)
s = s.replace('versionName = "0.6.5.1"', 'versionName = "0.6.5.2"', 1)
p.write_text(s)

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()
if 'Text("v0.6.5.1"' not in s:
    raise SystemExit('Visible v0.6.5.1 marker not found')
p.write_text(s.replace('Text("v0.6.5.1"', 'Text("v0.6.5.2"', 1))

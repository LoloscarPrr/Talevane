from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()

replacements = {
    'Text("v0.6.4.2"': 'Text("v0.6.5"',
    '"Música · ${moodSnapshot.mood.label} · sonando"': '"Piano · ${moodSnapshot.mood.label} · sonando"',
    '"Música · ${moodSnapshot.mood.label}"': '"Piano · ${moodSnapshot.mood.label}"',
    '"Volumen de música"': '"Volumen de piano"',
    'Text("Música · ${snapshot.mood.label}"': 'Text("Piano · ${snapshot.mood.label}"',
}

for old, new in replacements.items():
    if old not in s:
        raise SystemExit(f'marker not found: {old}')
    s = s.replace(old, new)

p.write_text(s)

from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()
old = 'Text("v0.6.8", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
new = 'Text("v0.6.9", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
if old not in s:
    raise SystemExit('Visible version marker not found')
s = s.replace(old, new, 1)
p.write_text(s)

from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()
old = 'Text("v0.6.7", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
new = 'Text("v0.6.7.1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)'
if old not in s:
    raise SystemExit('Visible version marker not found')
p.write_text(s.replace(old, new, 1))

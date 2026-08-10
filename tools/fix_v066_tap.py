from pathlib import Path
p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()
bad = ", ''',"
if bad not in s:
    raise SystemExit('Malformed apostrophe sequence not found')
s = s.replace(bad, ",", 1)
p.write_text(s)

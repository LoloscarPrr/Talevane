from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/audio/MasteringChain.kt')
s = p.read_text()
s = s.replace(' * Lightweight post-mix mastering for the generated soundtrack.',' * Clean post-mix mastering for the generated soundtrack.')
s = s.replace('                        // Keep this subtle: the dedicated bass line should gain weight, not boom.','                        // Keep this very subtle: the arrangement already has a dedicated bass line.')
s = s.replace('                    // Millibels. This is deliberately moderate to add density/headroom perception\n                    // without crushing the narration-facing soundtrack into obvious distortion.','                    // Small final lift only. Preserve transients and leave headroom for narration.')
# Replace the broad EQ method and gain functions with a cleaner curve.
start = s.index('        private fun targetBandGainMb(')
end = s.index('    }\n}', start)
new = '''        private fun targetBandGainMb(hz: Float, mood: ReadingMood): Int {
            // v0.6.9.1 deliberately avoids the heavy low-mid buildup heard in the first master.
            // Values are millibels: +/-100 = +/-1 dB.
            val sub = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> 35
                ReadingMood.MYSTERY -> 15
                else -> 0
            }
            val lowMid = when (mood) {
                ReadingMood.MYSTERY, ReadingMood.MELANCHOLY -> -115
                ReadingMood.TENSION -> -95
                else -> -75
            }
            val mid = when (mood) {
                ReadingMood.MYSTERY -> -70
                ReadingMood.MELANCHOLY -> -55
                else -> -40
            }
            val presence = when (mood) {
                ReadingMood.ACTION, ReadingMood.TENSION -> 105
                ReadingMood.MYSTERY -> 95
                ReadingMood.REFLECTIVE -> 90
                else -> 80
            }
            val air = when (mood) {
                ReadingMood.CALM, ReadingMood.WARMTH -> 125
                ReadingMood.MYSTERY, ReadingMood.MELANCHOLY -> 115
                else -> 105
            }

            return when {
                hz < 110f -> sub
                hz < 320f -> lowMid
                hz < 1000f -> mid
                hz < 4200f -> presence
                else -> air
            }
        }

        private fun bassStrength(mood: ReadingMood): Int = when (mood) {
            ReadingMood.ACTION -> 110
            ReadingMood.TENSION -> 95
            ReadingMood.MYSTERY -> 75
            ReadingMood.MELANCHOLY -> 65
            else -> 55
        }

        private fun loudnessGainMb(mood: ReadingMood): Int = when (mood) {
            ReadingMood.ACTION -> 105
            ReadingMood.TENSION -> 95
            ReadingMood.MYSTERY -> 85
            else -> 75
        }
'''
s = s[:start] + new + s[end:]
p.write_text(s)

p = Path('app/src/main/java/app/talevane/reader/audio/AmbientSoundEngine.kt')
s = p.read_text()
s = s.replace('v0.6.9 adds a post-mix mastering chain', 'v0.6.9.1 uses a cleaner post-mix mastering chain')
s = s.replace('targetVolume = 0.48f', 'targetVolume = 0.46f')
s = s.replace('targetVolume.toDouble().pow(0.60)', 'targetVolume.toDouble().pow(0.64)')
s = s.replace('val intensityTrim = 0.84f + targetIntensity.coerceIn(0f, 1f) * 0.10f', 'val intensityTrim = 0.82f + targetIntensity.coerceIn(0f, 1f) * 0.08f')
s = s.replace('return (perceptual * intensityTrim * 1.17f).coerceIn(0f, 0.95f)', 'return (perceptual * intensityTrim * 1.05f).coerceIn(0f, 0.86f)')
p.write_text(s)

p = Path('app/build.gradle.kts')
s = p.read_text().replace('versionCode = 21','versionCode = 22').replace('versionName = "0.6.9"','versionName = "0.6.9.1"')
p.write_text(s)

p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text().replace('Text("v0.6.9", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)','Text("v0.6.9.1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)')
p.write_text(s)

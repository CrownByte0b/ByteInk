# AndroidX Ink 1.1.0-alpha06 point blobs

Copied from the ViveNotes desktop app (`multiplataform-vive`, `shared/src/jvmTest/resources/ink/`),
where they were produced by AndroidX Ink 1.1.0-alpha06 — the Android app's version — running on
Linux with Google's own native library.

- `*.bin` are point blobs that library encoded, and `*.txt` what its decoder read back from them:
  a header line (tool type, stroke unit length, which optional channels are present), then one
  line per input: `x y elapsedTimeMillis pressure tilt orientation`, with -1 for an absent channel.
- `edges.txt` and `verdicts.txt` are uncompressed `CodedStrokeInputBatch` messages, crafted or
  mutated, one per line as `accept|reject <hex> [name]`: whether the library accepted them. The
  desktop app compared its own reader with the library on 12,000 encoded strokes and 312,000
  crafted, mutated and gzip-damaged messages; `verdicts.txt` is a sample of those.

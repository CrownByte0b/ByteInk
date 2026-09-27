# getting android-ink from google

AndroidX Ink's `ink/` sources at the pinned release, 1.1.0-alpha06: `frameworks/support` commit
`61ee8cd421d0` (see `upstream/pins.properties`), from AndroidX's GitHub mirror.

```bash
git init androidx-ink
cd androidx-ink
git remote add origin https://github.com/androidx/androidx.git
git sparse-checkout set --cone ink
git fetch --depth 1 --filter=blob:none origin 61ee8cd421d0c0252d8db0253b739de537999371
git checkout --detach FETCH_HEAD
```

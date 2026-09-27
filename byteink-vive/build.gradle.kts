// ViveNotes ink on the real engine: the brush catalog, the stored-row codecs and the replay of
// stored erase, move and resize operations, compatible with the Android app.

plugins {
    id("byteink.library")
}

dependencies {
    api(project(":byteink-core"))
}

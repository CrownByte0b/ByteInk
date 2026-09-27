// Desktop foundation over AndroidX Ink. It re-exports the pinned upstream JVM modules unchanged and
// will add the mesh-geometry adapter, hit-testing helpers and native-loader checks.

plugins {
    id("byteink.library")
}

dependencies {
    api(libs.androidx.ink.brush)
    api(libs.androidx.ink.geometry)
    api(libs.androidx.ink.storage)
    api(libs.androidx.ink.strokes)
}

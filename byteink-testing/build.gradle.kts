// Test support shared by byteink and its consumers: fixtures, a .vive reader for tests, geometry
// dumps and image comparison.

plugins {
    id("byteink.library")
}

dependencies {
    api(project(":byteink-vive"))
}

// Same toolchain as YMusic V4 (AGP 9 compiles Kotlin itself; the Kotlin plugin entry only pins its version).
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}

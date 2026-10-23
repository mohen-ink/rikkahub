plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.ssh"
}

dependencies {
    // JSch fork with secure defaults (SHA-1 signatures disabled), pure Java, no rootfs/system ssh.
    // https://github.com/mwiede/jsch
    implementation(libs.jsch)
    // BouncyCastle: required on Android for ed25519/curve25519 host keys and OpenSSH-format keys
    // (ART does not expose the EdDSA/XDH providers this library uses on Java 15+/11+).
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

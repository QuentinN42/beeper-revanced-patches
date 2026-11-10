extension {
    name = "extensions/extension.rve"
}

android {
    namespace = "app.revanced.extension"

    defaultConfig {
        // Notification#extras (API 19) and Notification#category (API 21) are
        // used by the extension; the target app requires far more.
        minSdk = 21
    }
}

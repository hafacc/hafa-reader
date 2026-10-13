package cc.hafa.reader.sync

import com.google.firebase.FirebaseOptions

/**
 * Public client config, safe to commit, mirroring `web/src/lib/config.ts`:
 * none of these values grants anything on its own; Firestore's rules
 * (`firebase/firestore.rules`) protect the data. Change a value here.
 */
object SyncConfig {
    /** Firebase API key (Project settings, General). */
    const val API_KEY = "AIzaSyAiZqy__USETcQZ9A2Ikbk-3N6pKLwIVoU"

    /** The Android app's Firebase app id, `1:…:android:…` (Project settings, Your apps). */
    const val APPLICATION_ID = "1:508750223929:android:496cca3af81fcc46240be8"

    /** Firebase project id. */
    const val PROJECT_ID = "hafa-reader"

    /** OAuth client id of the project's web client, which Google sign-in asks tokens for. */
    const val WEB_CLIENT_ID = "508750223929-mcb6p0vbm1c9iqgs9fa9hibegmaek77o.apps.googleusercontent.com"

    /** The privacy policy. */
    const val PRIVACY_URL = "https://reader.hafa.cc/privacy"

    /** The terms of service. */
    const val TERMS_URL = "https://reader.hafa.cc/terms"

    /** Firebase options from the values above. */
    fun firebaseOptions(): FirebaseOptions =
        FirebaseOptions.Builder()
            .setApiKey(API_KEY)
            .setApplicationId(APPLICATION_ID)
            .setProjectId(PROJECT_ID)
            .build()
}

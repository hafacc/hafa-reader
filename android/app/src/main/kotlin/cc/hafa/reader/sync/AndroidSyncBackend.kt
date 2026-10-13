package cc.hafa.reader.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.edit
import cc.hafa.reader.core.sync.AccountStore
import cc.hafa.reader.core.sync.SyncBackend
import cc.hafa.reader.core.sync.SyncPreferences
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Firestore for each account, and whether the device has a network. */
class AndroidSyncBackend(context: Context, private val db: FirebaseFirestore) : SyncBackend {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow(isOnline())

    override val online: StateFlow<Boolean> = mutableOnline.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mutableOnline.value = true
                }

                override fun onLost(network: Network) {
                    mutableOnline.value = isOnline()
                }
            },
        )
    }

    private fun isOnline(): Boolean =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun storeFor(uid: String): AccountStore = FirestoreAccountStore(db, uid)
}

/** Sync's small values in shared preferences. */
class SharedSyncPreferences(context: Context) : SyncPreferences {
    private val shared = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    override fun get(key: String): String? = shared.getString(key, null)

    override fun set(key: String, value: String?) {
        shared.edit {
            if (value == null) remove(key) else putString(key, value)
        }
    }
}

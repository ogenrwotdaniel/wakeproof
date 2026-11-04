package com.ogenrwotdaniel.wakeproof.clip

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import com.ogenrwotdaniel.wakeproof.data.sync.SyncAccount
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Hilt access for the non-injected hosts (the service hook, the tile). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClipEntryPoint {
    fun clipHub(): ClipHub
    fun accounts(): com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
}

/**
 * The always-on half of WakeProof Clip, hosted by `AppBlockService` (the
 * always-on foreground service on phone and tablet) through one
 * [start]/[stop] pair. While the feature is on and paired it runs:
 * the LAN server + mDNS, the Firestore clip listener and presence upkeep
 * (signed in only), and the screenshot observer (if allowed and granted).
 * Toggling the feature in Settings starts or stops these without a service restart.
 */
object ClipRuntime {
    private const val TAG = "ClipRuntime"
    private const val PRESENCE_EVERY_MILLIS = 30 * 60 * 1000L
    private const val LISTENER_REFRESH_MILLIS = 10 * 60 * 1000L

    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val entry = EntryPointAccessors.fromApplication(app, ClipEntryPoint::class.java)
        job = scope.launch(Dispatchers.Default) {
            runCatching { run(app, entry.clipHub(), entry.accounts()) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w(TAG, "stopped", it) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run(
        context: Context,
        hub: ClipHub,
        accounts: com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
    ) {
        combine(hub.keys.config, accounts.account) { cfg, account ->
            Triple(cfg.active, cfg.sendScreenshots, (account as? SyncAccount.SignedIn)?.uid)
        }.distinctUntilChanged().collectLatest { (active, screenshots, uid) ->
            if (!active) return@collectLatest
            coroutineScope {
                launch { lan(hub) }
                if (uid != null) {
                    launch { presence(context, hub) }
                    launch { listen(hub) }
                }
                if (screenshots) launch { screenshots(context, hub) }
            }
        }
    }

    private suspend fun lan(hub: ClipHub) {
        val server = ClipLanServer { id, body -> hub.receiveSealed(id, body) }
        server.start(hub.scope)
        hub.discovery.start(hub.installId(), hub.deviceName())
        try {
            awaitCancellation()
        } finally {
            server.stop()
            hub.discovery.stop()
        }
    }

    /** Rewritten on start, on network change, and at least every 30 min. */
    private suspend fun presence(context: Context, hub: ClipHub) {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                changes.trySend(Unit)
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                changes.trySend(Unit)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        try {
            while (true) {
                withContext(Dispatchers.IO) { hub.writePresence() }
                withTimeoutOrNull(PRESENCE_EVERY_MILLIS) { changes.receive() }
                // A network change arrives as a burst; let it settle before writing.
                delay(5_000)
                changes.tryReceive()
            }
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * Snapshot listener on `clip_` headers newer than 10 minutes ago. Re-registered
     * every 10 minutes from the newest header seen, so it does not keep an hour of
     * expired headers in view.
     */
    private suspend fun listen(hub: ClipHub) {
        val cloud = hub.cloud() ?: return
        var since = System.currentTimeMillis() - ClipRules.STALE_AFTER_MILLIS
        while (true) {
            var registration: ListenerRegistration? = null
            try {
                registration = cloud.listen(since) { header ->
                    if (header.createdAt > since) since = header.createdAt
                    hub.scope.launch { hub.receiveCloud(cloud, header) }
                }
                delay(LISTENER_REFRESH_MILLIS)
            } finally {
                registration?.remove()
            }
            since = maxOf(since, System.currentTimeMillis() - ClipRules.STALE_AFTER_MILLIS)
        }
    }

    private suspend fun screenshots(context: Context, hub: ClipHub) {
        val observer = ClipScreenshots(context) { uri, mime ->
            hub.scope.launch {
                ClipTriggers.sendLocal(context, hub, LocalClip.Image(uri, mime), explicit = false)
            }
        }
        withContext(Dispatchers.Main) { observer.start() }
        try {
            awaitCancellation()
        } finally {
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { observer.stop() }
        }
    }
}

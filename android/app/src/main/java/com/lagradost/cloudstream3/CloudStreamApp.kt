package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import okio.Path.Companion.toPath
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import io.github.sherazi5110.cloudstream.App

/**
 * The application class. It keeps upstream's name and companion API
 * (app/.../CloudStreamApp.kt), because extensions call CloudStreamApp.getKey/setKey/context.
 */
class CloudStreamApp : Application(), coil3.SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        context = this
        App.init(this)
    }

    /**
     * The one image loader for the whole app. Bounded memory cache (big TV boxes keep 1/8 heap for posters,
     * Android Go boxes 1/20), weak references (no strong refs pinning recycled bitmaps) and a bigger disk cache
     * than upstream default only where the app has storage to burn. The app's own OkHttp client is used, so
     * posters honour the same DNS/cookies as everything else.
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader {
        val app = context as Context
        val low = io.github.sherazi5110.cloudstream.player.DeviceTier.tier == io.github.sherazi5110.cloudstream.player.DeviceTier.Tier.LOW
        return coil3.ImageLoader.Builder(context)
            .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory({ com.lagradost.cloudstream3.app.baseClient })) }
            .memoryCache(coil3.memory.MemoryCache.Builder()
                .maxSizePercent(context, if (low) 0.05 else 0.09)
                .strongReferencesEnabled(false)
                .build())
            .diskCache(coil3.disk.DiskCache.Builder()
                .directory(app.cacheDir.resolve("image_cache").absolutePath.toPath())
                .maxSizeBytes(if (low) 48L * 1024 * 1024 else 96L * 1024 * 1024)
                .build())
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            // Free everything the OS might be about to need back (Android Go boxes kill the app otherwise).
            runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
            runCatching { com.lagradost.cloudstream3.app.baseClient.connectionPool.evictAll() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        onTrimMemory(TRIM_MEMORY_COMPLETE)
    }

    companion object {
        var context: Context? = null
            set(value) {
                field = value
                setContext(value)
            }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? = context?.getKey(path, valueType)
        fun <T : Any> setKeyClass(path: String, value: T) { context?.setKey(path, value) }
        fun removeKeys(folder: String): Int? = context?.removeKeys(folder)
        fun <T> setKey(path: String, value: T) { context?.setKey(path, value) }
        fun <T> setKey(folder: String, path: String, value: T) { context?.setKey(folder, path, value) }
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = context?.getKey(path, defVal)
        inline fun <reified T : Any> getKey(path: String): T? = context?.getKey(path)
        inline fun <reified T : Any> getKey(folder: String, path: String): T? = context?.getKey(folder, path)
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            context?.getKey(folder, path, defVal)
        fun getKeys(folder: String): List<String>? = context?.getKeys(folder)
        fun removeKey(folder: String, path: String) { context?.removeKey(folder, path) }
        fun removeKey(path: String) { context?.removeKey(path) }

        fun openBrowser(url: String, activity: Activity?) {
            val c = activity ?: context ?: return
            runCatching {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }
}

/** Deprecated upstream alias that older plugins still reference. */
class AcraApplication {
    companion object {
        val context get() = CloudStreamApp.context
        fun removeKeys(folder: String): Int? = CloudStreamApp.removeKeys(folder)
        fun <T> setKey(path: String, value: T) = CloudStreamApp.setKey(path, value)
        fun <T> setKey(folder: String, path: String, value: T) = CloudStreamApp.setKey(folder, path, value)
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = CloudStreamApp.getKey(path, defVal)
        inline fun <reified T : Any> getKey(path: String): T? = CloudStreamApp.getKey(path)
        inline fun <reified T : Any> getKey(folder: String, path: String): T? = CloudStreamApp.getKey(folder, path)
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            CloudStreamApp.getKey(folder, path, defVal)
    }
}

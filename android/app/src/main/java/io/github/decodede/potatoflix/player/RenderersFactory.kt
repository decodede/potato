package io.github.decodede.potatoflix.player

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * FFmpeg extension renderers (software decoding of AV1/HEVC/VP9/... on weak CPUs), with a plain
 * [TextRenderer]: nextlib's own text renderer fails with ERROR_CODE_FAILED_RUNTIME_CHECK
 * (nextlib PR #158, fixed upstream of the pinned version).
 */
@OptIn(UnstableApi::class)
internal class FixedNextRenderersFactory(context: Context) : NextRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(TextRenderer(output, outputLooper))
    }
}

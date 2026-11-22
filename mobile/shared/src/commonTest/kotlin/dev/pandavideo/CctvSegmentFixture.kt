package dev.pandavideo

import dev.pandavideo.core.model.MediaSegment
import dev.pandavideo.core.player.MediaSource

/** Explicit public sample used only by opt-in native decoder tests. */
internal fun cctvSegmentFixture(): MediaSource {
    val prefix = "https://vod.cntv.lxdns.com/flash/mp4video63/TMS/2026/11/21/" +
        "d70ba25b901c4acdad2c22384c9b2d12_h2642000000nero_aac16"
    return MediaSource(100, "https://unused.invalid/full.m3u8", "hls", "Public CCTV segmented fixture",
        startPosition = 118_500, segments = (1..15).map {
            MediaSegment("$prefix-$it.mp4", if (it == 15) 119_160 else 120_000)
        })
}

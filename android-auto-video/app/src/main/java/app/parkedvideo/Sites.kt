package app.parkedvideo

import java.net.URLEncoder

/** Video sites offered in the car's site list. All play without DRM in a WebView. */
object Sites {
    data class Site(val name: String, val url: String)

    const val HOME = "https://m.youtube.com/"

    val ALL = listOf(
        Site("YouTube", HOME),
        Site("Vimeo", "https://vimeo.com/watch"),
        Site("Twitch", "https://m.twitch.tv/"),
        Site("Dailymotion", "https://www.dailymotion.com/"),
        Site("TED Talks", "https://www.ted.com/talks"),
    )

    /** Turns typed text into a URL: web addresses load directly, anything else searches YouTube. */
    fun urlFor(input: String): String {
        val text = input.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        if (!text.contains(' ') && text.contains('.')) return "https://$text"
        return "https://m.youtube.com/results?search_query=" + URLEncoder.encode(text, "UTF-8")
    }
}

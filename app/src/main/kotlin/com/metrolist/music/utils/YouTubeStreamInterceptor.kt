package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import okhttp3.Interceptor
import okhttp3.Response

/**
 * An OkHttp interceptor that sets the correct User-Agent on requests to googlevideo.com
 * based on the client parameter (?c=...) in the stream URL.
 * Without this matching User-Agent, YouTube CDN returns HTTP 403 Forbidden.
 */
object YouTubeStreamInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host

        if (host.contains("googlevideo.com")) {
            val clientParam = url.queryParameter("c")?.uppercase()
            val userAgent = when {
                clientParam?.contains("IOS") == true ->
                    "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
                clientParam?.contains("ANDROID_VR") == true ->
                    "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)"
                clientParam?.contains("ANDROID") == true ->
                    "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
                clientParam?.contains("TV") == true ->
                    "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15"
                else ->
                    com.metrolist.innertube.models.YouTubeClient.USER_AGENT_WEB
            }

            val builder = request.newBuilder()
                .header("User-Agent", userAgent)

            if (clientParam == null || clientParam.contains("WEB")) {
                builder.header("Origin", com.metrolist.innertube.models.YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
                builder.header("Referer", com.metrolist.innertube.models.YouTubeClient.REFERER_YOUTUBE_MUSIC)
            }

            // NOTE: Do NOT add YouTube session cookies to googlevideo.com requests.
            // Regular YouTube stream URLs do not use web cookies, and sending cookies
            // to Google Video CDN edge servers causes HTTP 403 Forbidden.

            return chain.proceed(builder.build())
        }

        return chain.proceed(request)
    }
}

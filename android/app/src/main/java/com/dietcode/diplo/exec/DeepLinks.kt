package com.dietcode.diplo.exec

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.net.URLEncoder

/**
 * Opens an app already on the screen a task needs, so the on-device agent has one or two steps
 * left instead of five: Uber with the drop set, YouTube on its results, the right Settings page.
 * Apps without a public link (Rapido, Ola, Swiggy) just open, and the agent does the rest.
 */
object DeepLinks {

    data class Link(val uri: String, val pkg: String?)

    /** A link into [app] for these task params, or null when the app should just be launched. */
    fun link(app: String, params: Map<String, String>): Link? {
        val name = app.lowercase()
        val destination = params["destination"].orEmpty().trim()
        val query = params["query"].orEmpty().trim()
        return when {
            // https://developer.uber.com/docs/riders/ride-requests/tutorials/deep-links
            "uber" in name && destination.isNotEmpty() -> Link(
                "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[formatted_address]=${enc(destination)}",
                "com.ubercab",
            )
            "map" in name && destination.isNotEmpty() ->
                Link("google.navigation:q=${enc(destination)}", "com.google.android.apps.maps")
            "youtube" in name && query.isNotEmpty() ->
                Link("https://www.youtube.com/results?search_query=${enc(query)}", "com.google.android.youtube")
            "play store" in name && query.isNotEmpty() -> Link("market://search?q=${enc(query)}", "com.android.vending")
            else -> null
        }
    }

    /** The Settings page for what the task is about ("Dark theme" lives under Display), or null. */
    fun settingsAction(params: Map<String, String>): String? {
        if (params["app"].orEmpty().lowercase().let { "setting" !in it }) return null
        val about = "${params["target"].orEmpty()} ${params["goal"].orEmpty()}".lowercase()
        return when {
            listOf("dark", "theme", "display", "brightness", "font", "screen timeout").any { it in about } ->
                Settings.ACTION_DISPLAY_SETTINGS
            "wi-fi" in about || "wifi" in about -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" in about -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" in about -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            listOf("sound", "ringtone", "vibrat").any { it in about } -> Settings.ACTION_SOUND_SETTINGS
            "location" in about -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
    }

    fun intent(link: Link): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.uri)).apply { link.pkg?.let(::setPackage) }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

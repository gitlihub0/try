package com.anizium

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniziumPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Anizium())
    }
}

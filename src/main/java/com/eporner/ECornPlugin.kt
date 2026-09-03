package com.eporner

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ECornPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ECornProvider())
    }
}

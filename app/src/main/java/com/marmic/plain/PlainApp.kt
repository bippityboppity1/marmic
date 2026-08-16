package com.marmic.plain

import android.app.Application
import com.marmic.plain.data.AppRepository
import com.marmic.plain.data.LayoutRepository
import com.marmic.plain.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class PlainApp : Application() {

    private val scope = CoroutineScope(SupervisorJob())

    lateinit var appRepository: AppRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var layoutRepository: LayoutRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        layoutRepository = LayoutRepository(this)
        appRepository = AppRepository(this, scope).also { it.start() }
    }
}

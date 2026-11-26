package com.stealth.vaultnotes

import android.app.Application

class VaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReportManager.install(this)
        CrashReportManager.recoverPreviousUnexpectedExit(this)
        CrashReportManager.publishReportsToDownloads(this)
        CrashReportManager.beginSession(this)
    }
}

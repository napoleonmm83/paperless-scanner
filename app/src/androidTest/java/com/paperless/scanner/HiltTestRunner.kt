package com.paperless.scanner

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: android.os.Bundle?) {
        super.onCreate(arguments)
        // The target manifest disables WorkManager's initializer. HiltTestApplication
        // does not implement Configuration.Provider like PaperlessApp, so background
        // services must have an initialized instance during instrumentation.
        WorkManager.initialize(targetContext, Configuration.Builder().build())
    }

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}

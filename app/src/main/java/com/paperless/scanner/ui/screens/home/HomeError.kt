package com.paperless.scanner.ui.screens.home

sealed class HomeError {
    data class LoadFailed(val source: String, val cause: Throwable) : HomeError()
    data class ActionFailed(val action: String, val cause: Throwable) : HomeError()
}

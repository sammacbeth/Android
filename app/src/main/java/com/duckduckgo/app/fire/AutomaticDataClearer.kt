/*
 * Copyright (c) 2018 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.fire

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.core.os.postDelayed
import androidx.lifecycle.*
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.duckduckgo.app.global.ApplicationClearDataState
import com.duckduckgo.app.global.ApplicationClearDataState.FINISHED
import com.duckduckgo.app.global.ApplicationClearDataState.INITIALIZING
import com.duckduckgo.app.global.view.ClearDataAction
import com.duckduckgo.app.settings.clear.ClearWhatOption
import com.duckduckgo.app.settings.clear.ClearWhenOption
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.browser.api.BrowserLifecycleObserver
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.SingleInstanceIn
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

interface DataClearer {
    val dataClearerState: LiveData<ApplicationClearDataState>
    var isFreshAppLaunch: Boolean
}

@ContributesBinding(
    scope = AppScope::class,
    boundType = DataClearer::class
)
@ContributesMultibinding(
    scope = AppScope::class,
    boundType = BrowserLifecycleObserver::class
)
@SingleInstanceIn(AppScope::class)
class AutomaticDataClearer @Inject constructor(
    private val workManager: WorkManager,
    private val settingsDataStore: SettingsDataStore,
    private val clearDataAction: ClearDataAction,
    private val dataClearerTimeKeeper: BackgroundTimeKeeper,
    private val dataClearerForegroundAppRestartPixel: DataClearerForegroundAppRestartPixel
) : DataClearer, BrowserLifecycleObserver, CoroutineScope {

    private val clearJob: Job = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + clearJob

    override val dataClearerState: MutableLiveData<ApplicationClearDataState> = MutableLiveData<ApplicationClearDataState>().also {
        it.postValue(INITIALIZING)
    }

    override var isFreshAppLaunch = true

    override fun onOpen(isFreshLaunch: Boolean) {
        isFreshAppLaunch = isFreshLaunch
        launch {
            onAppForegroundedAsync()
        }
    }

    @UiThread
    @VisibleForTesting
    suspend fun onAppForegroundedAsync() {
        dataClearerState.value = INITIALIZING

        Timber.i("onAppForegrounded; is from fresh app launch? $isFreshAppLaunch")

        workManager.cancelAllWorkByTag(DataClearingWorker.WORK_REQUEST_TAG)

        val appUsedSinceLastClear = settingsDataStore.appUsedSinceLastClear
        settingsDataStore.appUsedSinceLastClear = true

        val appIconChanged = settingsDataStore.appIconChanged
        settingsDataStore.appIconChanged = false

        val clearWhat = settingsDataStore.automaticallyClearWhatOption
        val clearWhen = settingsDataStore.automaticallyClearWhenOption
        Timber.i("Currently configured to automatically clear $clearWhat / $clearWhen")

        if (clearWhat == ClearWhatOption.CLEAR_NONE) {
            Timber.i("No data will be cleared as it's configured to clear nothing automatically")
            dataClearerState.value = FINISHED
        } else {
            if (shouldClearData(clearWhen, appUsedSinceLastClear, appIconChanged)) {
                Timber.i("Decided data should be cleared")
                clearDataWhenAppInForeground(clearWhat)
            } else {
                Timber.i("Decided not to clear data at this time")
                dataClearerState.value = FINISHED
            }
        }

        isFreshAppLaunch = false
        settingsDataStore.clearAppBackgroundTimestamp()
    }

    override fun onClose() {
        val timeNow = SystemClock.elapsedRealtime()
        Timber.i("Recording when app backgrounded ($timeNow)")

        dataClearerState.value = INITIALIZING

        settingsDataStore.appBackgroundedTimestamp = timeNow

        val clearWhenOption = settingsDataStore.automaticallyClearWhenOption
        val clearWhatOption = settingsDataStore.automaticallyClearWhatOption

        if (clearWhatOption == ClearWhatOption.CLEAR_NONE || clearWhenOption == ClearWhenOption.APP_EXIT_ONLY) {
            Timber.d("No background timer required for current configuration: $clearWhatOption / $clearWhenOption")
        } else {
            scheduleBackgroundTimerToTriggerClear(clearWhenOption.durationMilliseconds())
        }
    }

    override fun onExit() {
        // the app does not have any activity in CREATED state we kill the process
        if (settingsDataStore.automaticallyClearWhatOption != ClearWhatOption.CLEAR_NONE) {
            clearDataAction.killProcess()
        }
    }

    private fun scheduleBackgroundTimerToTriggerClear(durationMillis: Long) {
        workManager.also {
            val workRequest = OneTimeWorkRequestBuilder<DataClearingWorker>()
                .setInitialDelay(durationMillis, TimeUnit.MILLISECONDS)
                .addTag(DataClearingWorker.WORK_REQUEST_TAG)
                .build()
            it.enqueue(workRequest)
            Timber.i(
                "Work request scheduled, ${durationMillis}ms from now, " +
                    "to clear data if the user hasn't returned to the app. job id: ${workRequest.id}"
            )
        }
    }

    @UiThread
    @Suppress("NON_EXHAUSTIVE_WHEN")
    private suspend fun clearDataWhenAppInForeground(clearWhat: ClearWhatOption) {
        Timber.i("Clearing data when app is in the foreground: $clearWhat")

        when (clearWhat) {
            ClearWhatOption.CLEAR_TABS_ONLY -> {

                withContext(Dispatchers.IO) {
                    clearDataAction.clearTabsAsync(true)
                }

                withContext(Dispatchers.Main) {
                    Timber.i("Notifying listener that clearing has finished")
                    dataClearerState.value = FINISHED
                }
            }

            ClearWhatOption.CLEAR_TABS_AND_DATA -> {
                val processNeedsRestarted = !isFreshAppLaunch
                Timber.i("App is in foreground; restart needed? $processNeedsRestarted")

                clearDataAction.clearTabsAndAllDataAsync(appInForeground = true, shouldFireDataClearPixel = false)

                Timber.i("All data now cleared, will restart process? $processNeedsRestarted")
                if (processNeedsRestarted) {
                    clearDataAction.setAppUsedSinceLastClearFlag(false)
                    dataClearerForegroundAppRestartPixel.incrementCount()
                    // need a moment to draw background color (reduces flickering UX)
                    Handler().postDelayed(100) {
                        Timber.i("Will now restart process")
                        clearDataAction.killAndRestartProcess(notifyDataCleared = true)
                    }
                } else {
                    Timber.i("Will not restart process")
                    dataClearerState.value = FINISHED
                }
            }
            else -> {}
        }
    }

    private fun shouldClearData(
        cleanWhenOption: ClearWhenOption,
        appUsedSinceLastClear: Boolean,
        appIconChanged: Boolean
    ): Boolean {
        Timber.d("Determining if data should be cleared for option $cleanWhenOption")

        if (!appUsedSinceLastClear) {
            Timber.d("App hasn't been used since last clear; no need to clear again")
            return false
        }

        Timber.d("App has been used since last clear")

        if (isFreshAppLaunch) {
            Timber.d("This is a fresh app launch, so will clear the data")
            return true
        }

        if (appIconChanged) {
            Timber.i("No data will be cleared as the app icon was just changed")
            return false
        }

        if (cleanWhenOption == ClearWhenOption.APP_EXIT_ONLY) {
            Timber.d("This is NOT a fresh app launch, and the configuration is for app exit only. Not clearing the data")
            return false
        }
        if (!settingsDataStore.hasBackgroundTimestampRecorded()) {
            Timber.w("No background timestamp recorded; will not clear the data")
            return false
        }

        val enoughTimePassed = dataClearerTimeKeeper.hasEnoughTimeElapsed(
            backgroundedTimestamp = settingsDataStore.appBackgroundedTimestamp,
            clearWhenOption = cleanWhenOption
        )
        Timber.d("Has enough time passed to trigger the data clear? $enoughTimePassed")

        return enoughTimePassed
    }
}

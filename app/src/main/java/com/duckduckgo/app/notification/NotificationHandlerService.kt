/*
 * Copyright (c) 2019 DuckDuckGo
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

package com.duckduckgo.app.notification

import android.app.IntentService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.global.plugins.PluginPoint
import com.duckduckgo.app.icon.ui.ChangeIconActivity
import com.duckduckgo.app.notification.NotificationHandlerService.NotificationEvent.APPTP_WAITLIST_CODE
import com.duckduckgo.app.notification.NotificationHandlerService.NotificationEvent.CANCEL
import com.duckduckgo.app.notification.NotificationHandlerService.NotificationEvent.CHANGE_ICON_FEATURE
import com.duckduckgo.app.notification.NotificationHandlerService.NotificationEvent.CLEAR_DATA_LAUNCH
import com.duckduckgo.app.notification.NotificationHandlerService.NotificationEvent.WEBSITE
import com.duckduckgo.app.notification.NotificationHandlerService.NotificationEvent.APP_LAUNCH
import com.duckduckgo.app.notification.model.NotificationSpec
import com.duckduckgo.app.notification.model.SchedulableNotificationPlugin
import com.duckduckgo.app.notification.model.WebsiteNotificationSpecification
import com.duckduckgo.app.pixels.AppPixelName.NOTIFICATION_CANCELLED
import com.duckduckgo.app.pixels.AppPixelName.NOTIFICATION_LAUNCHED
import com.duckduckgo.app.settings.SettingsActivity
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.waitlist.trackerprotection.ui.AppTPWaitlistActivity
import com.duckduckgo.di.scopes.ActivityScope
import dagger.android.AndroidInjection
import timber.log.Timber
import javax.inject.Inject

@InjectWith(ActivityScope::class)
class NotificationHandlerService : IntentService("NotificationHandlerService") {

    @Inject
    lateinit var pixel: Pixel

    @Inject
    lateinit var context: Context

    @Inject
    lateinit var notificationManager: NotificationManagerCompat

    @Inject
    lateinit var notificationScheduler: AndroidNotificationScheduler

    @Inject
    lateinit var dispatcher: DispatcherProvider

    @Inject
    lateinit var taskStackBuilderFactory: TaskStackBuilderFactory

    @Inject
    lateinit var schedulableNotificationPluginPoint: PluginPoint<SchedulableNotificationPlugin>

    override fun onCreate() {
        super.onCreate()
        AndroidInjection.inject(this)
    }

    @VisibleForTesting
    public override fun onHandleIntent(intent: Intent?) {
        val pixelSuffix = intent?.getStringExtra(PIXEL_SUFFIX_EXTRA) ?: return

        when (intent.type) {
            APP_LAUNCH -> onAppLaunched(pixelSuffix)
            CLEAR_DATA_LAUNCH -> onClearDataLaunched(pixelSuffix)
            CANCEL -> onCancelled(pixelSuffix)
            WEBSITE -> onWebsiteNotification(intent, pixelSuffix)
            CHANGE_ICON_FEATURE -> onCustomizeIconLaunched(pixelSuffix)
            APPTP_WAITLIST_CODE -> onAppTPWaitlistCodeReceived(pixelSuffix)
            else -> {
                schedulableNotificationPluginPoint.getPlugins().forEach {
                    when (intent.type) {
                        it.getSchedulableNotification().launchIntent -> {
                            it.onNotificationLaunched()
                        }
                        it.getSchedulableNotification().cancelIntent -> {
                            it.onNotificationCancelled()
                        }
                    }
                }
            }
        }

        if (intent.getBooleanExtra(NOTIFICATION_AUTO_CANCEL, true)) {
            val notificationId = intent.getIntExtra(NOTIFICATION_SYSTEM_ID_EXTRA, 0)
            clearNotification(notificationId)
        }
    }

    private fun onAppTPWaitlistCodeReceived(pixelSuffix: String) {
        Timber.i("App Tracking Protection waitlist code received launched!")
        val intent = AppTPWaitlistActivity.intent(context)
        taskStackBuilderFactory.createTaskBuilder()
            .addNextIntentWithParentStack(intent)
            .startActivities()
        onLaunched(pixelSuffix)
    }

    private fun onWebsiteNotification(
        intent: Intent,
        pixelSuffix: String
    ) {
        val url = intent.getStringExtra(WebsiteNotificationSpecification.WEBSITE_KEY)
        val newIntent = BrowserActivity.intent(context, queryExtra = url)
        taskStackBuilderFactory.createTaskBuilder()
            .addNextIntentWithParentStack(newIntent)
            .startActivities()
        onLaunched(pixelSuffix)
    }

    private fun onCustomizeIconLaunched(pixelSuffix: String) {
        val intent = ChangeIconActivity.intent(context)
        taskStackBuilderFactory.createTaskBuilder()
            .addNextIntentWithParentStack(intent)
            .startActivities()
        onLaunched(pixelSuffix)
    }

    private fun onAppLaunched(pixelSuffix: String) {
        val intent = BrowserActivity.intent(context, newSearch = true)
        taskStackBuilderFactory.createTaskBuilder()
            .addNextIntentWithParentStack(intent)
            .startActivities()
        onLaunched(pixelSuffix)
    }

    private fun onClearDataLaunched(pixelSuffix: String) {
        Timber.i("Clear Data Launched!")
        val intent = SettingsActivity.intent(context)
        taskStackBuilderFactory.createTaskBuilder()
            .addNextIntentWithParentStack(intent)
            .startActivities()
        onLaunched(pixelSuffix)
    }

    private fun onCancelled(pixelSuffix: String) {
        pixel.fire("${NOTIFICATION_CANCELLED.pixelName}_$pixelSuffix")
    }

    private fun onLaunched(pixelSuffix: String) {
        pixel.fire("${NOTIFICATION_LAUNCHED.pixelName}_$pixelSuffix")
    }

    private fun clearNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    object NotificationEvent {
        const val APP_LAUNCH = "com.duckduckgo.notification.launch.app"
        const val CLEAR_DATA_LAUNCH = "com.duckduckgo.notification.launch.clearData"
        const val CANCEL = "com.duckduckgo.notification.cancel"
        const val WEBSITE = "com.duckduckgo.notification.website"
        const val CHANGE_ICON_FEATURE = "com.duckduckgo.notification.app.feature.changeIcon"
        const val APPTP_WAITLIST_CODE = "com.duckduckgo.notification.apptp.waitlist.code"
    }

    companion object {
        const val PIXEL_SUFFIX_EXTRA = "PIXEL_SUFFIX_EXTRA"
        const val NOTIFICATION_SYSTEM_ID_EXTRA = "NOTIFICATION_SYSTEM_ID"
        const val NOTIFICATION_AUTO_CANCEL = "NOTIFICATION_AUTO_CANCEL"

        fun pendingNotificationHandlerIntent(
            context: Context,
            eventType: String,
            specification: NotificationSpec
        ): PendingIntent {
            val intent = Intent(context, NotificationHandlerService::class.java)
            intent.type = eventType
            intent.putExtras(specification.bundle)
            intent.putExtra(PIXEL_SUFFIX_EXTRA, specification.pixelSuffix)
            intent.putExtra(NOTIFICATION_SYSTEM_ID_EXTRA, specification.systemId)
            intent.putExtra(NOTIFICATION_AUTO_CANCEL, specification.autoCancel)
            return PendingIntent.getService(context, 0, intent, 0)!!
        }
    }
}

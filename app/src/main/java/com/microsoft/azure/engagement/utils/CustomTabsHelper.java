// Copyright 2015 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.microsoft.azure.engagement.utils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.support.customtabs.CustomTabsClient;
import android.support.customtabs.CustomTabsServiceConnection;
import android.text.TextUtils;
import android.util.Log;

/**
 * Helper class for Custom Tabs.
 */
final class CustomTabsHelper {

    /**
     * Callback for events when connecting and disconnecting from Custom Tabs Service.
     */
    public interface ServiceConnectionCallback {

        /**
         * Called when the service is connected.
         *
         * @param client a CustomTabsClient
         */
        void onServiceConnected(CustomTabsClient client);

        /**
         * Called when the service is disconnected.
         */
        void onServiceDisconnected();
    }

    /**
     * Implementation for the CustomTabsServiceConnection that avoids leaking the ServiceConnectionCallback
     */
    public static final class ServiceConnection
            extends CustomTabsServiceConnection {

        // A weak reference to the ServiceConnectionCallback to avoid leaking it.
        private final WeakReference<ServiceConnectionCallback> connectionCallbackWeakReference;

        public ServiceConnection(ServiceConnectionCallback connectionCallback) {
            connectionCallbackWeakReference = new WeakReference<>(connectionCallback);
        }

        @Override
        public void onCustomTabsServiceConnected(ComponentName name, CustomTabsClient client) {
            final ServiceConnectionCallback connectionCallback = connectionCallbackWeakReference.get();
            if (connectionCallback != null) {
                connectionCallback.onServiceConnected(client);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            final ServiceConnectionCallback connectionCallback = connectionCallbackWeakReference.get();
            if (connectionCallback != null) {
                connectionCallback.onServiceDisconnected();
            }
        }
    }

    private static final String TAG = "CustomTabsHelper";
    private static final String STABLE_PACKAGE = "com.android.chrome";
    private static final String BETA_PACKAGE = "com.chrome.beta";
    private static final String DEV_PACKAGE = "com.chrome.dev";
    private static final String LOCAL_PACKAGE = "com.google.android.apps.chrome";
    private static final String ACTION_CUSTOM_TABS_CONNECTION = "android.support.customtabs.action.CustomTabsService";
    private static String sPackageNameToUse;

    private CustomTabsHelper() {
    }

    /**
     * Goes through all apps that handle VIEW intents and have a warmup service. Picks the one chosen by the user if there is one, otherwise makes a
     * best effort to return a valid package name.
     * <p/>
     * This is <strong>not</strong> threadsafe.
     *
     * @param context {@link Context} to use for accessing {@link PackageManager}.
     * @return The package name recommended to use for connecting to custom tabs related components.
     */
    public static final String getPackageNameToUse(Context context) {
        if (sPackageNameToUse != null) {
            return sPackageNameToUse;
        }

        final PackageManager packageManager = context.getPackageManager();
        // Get default VIEW intent handler.
        final Intent activityIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"));
        final ResolveInfo defaultViewHandlerInfo = packageManager.resolveActivity(activityIntent, 0);

        String defaultViewHandlerPackageName = null;
        if (defaultViewHandlerInfo != null) {
            defaultViewHandlerPackageName = defaultViewHandlerInfo.activityInfo.packageName;
        }

        // Get all apps that can handle VIEW intents.
        final List<ResolveInfo> resolvedActivityList = packageManager.queryIntentActivities(activityIntent, 0);
        final List<String> packagesSupportingCustomTabs = new ArrayList<>();
        for (ResolveInfo info : resolvedActivityList) {
            final Intent serviceIntent = new Intent();
            serviceIntent.setAction(ACTION_CUSTOM_TABS_CONNECTION);
            serviceIntent.setPackage(info.activityInfo.packageName);
            if (packageManager.resolveService(serviceIntent, 0) != null) {
                packagesSupportingCustomTabs.add(info.activityInfo.packageName);
            }
        }

        // Now packagesSupportingCustomTabs contains all apps that can handle both VIEW intents
        // and service calls.
        if (packagesSupportingCustomTabs.isEmpty()) {
            sPackageNameToUse = null;
        } else if (packagesSupportingCustomTabs.size() == 1) {
            sPackageNameToUse = packagesSupportingCustomTabs.get(0);
        } else if (!TextUtils.isEmpty(defaultViewHandlerPackageName) && !hasSpecializedHandlerIntents(context, activityIntent) && packagesSupportingCustomTabs.contains(defaultViewHandlerPackageName)) {
            sPackageNameToUse = defaultViewHandlerPackageName;
        } else if (packagesSupportingCustomTabs.contains(STABLE_PACKAGE)) {
            sPackageNameToUse = STABLE_PACKAGE;
        } else if (packagesSupportingCustomTabs.contains(BETA_PACKAGE)) {
            sPackageNameToUse = BETA_PACKAGE;
        } else if (packagesSupportingCustomTabs.contains(DEV_PACKAGE)) {
            sPackageNameToUse = DEV_PACKAGE;
        } else if (packagesSupportingCustomTabs.contains(LOCAL_PACKAGE)) {
            sPackageNameToUse = LOCAL_PACKAGE;
        }
        return sPackageNameToUse;
    }

    /**
     * Used to check whether there is a specialized handler for a given intent.
     *
     * @param intent The intent to check with.
     * @return Whether there is a specialized handler for the given intent.
     */
    private static final boolean hasSpecializedHandlerIntents(Context context, Intent intent) {
        try {
            final PackageManager packageManager = context.getPackageManager();
            final List<ResolveInfo> handlers = packageManager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER);
            if (handlers == null || handlers.size() == 0) {
                return false;
            }
            for (final ResolveInfo resolveInfo : handlers) {
                final IntentFilter filter = resolveInfo.filter;
                if (filter == null) {
                    continue;
                }
                if (filter.countDataAuthorities() == 0 || filter.countDataPaths() == 0) {
                    continue;
                }
                if (resolveInfo.activityInfo == null) {
                    continue;
                }
                return true;
            }
        } catch (RuntimeException e) {
            Log.e(CustomTabsHelper.TAG, "Runtime exception while getting specialized handlers");
        }
        return false;
    }
}

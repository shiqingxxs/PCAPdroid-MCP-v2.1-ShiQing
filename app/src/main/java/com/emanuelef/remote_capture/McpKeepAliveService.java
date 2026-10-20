/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.emanuelef.remote_capture.activities.MainActivity;

/**
 * Lightweight foreground service which keeps the process (and thus the embedded
 * MCP HTTP+SSE server) alive while the app is in the background or during Doze.
 *
 * It only runs while the MCP server is enabled and is stopped as soon as the MCP
 * server is disabled (see MainActivity.startMcpServer / stopMcpServer), so it
 * does not consume any resources when MCP is off. A PARTIAL_WAKE_LOCK keeps the
 * CPU awake so the server stays reachable; the rest is a low-priority ongoing
 * notification.
 */
public class McpKeepAliveService extends Service {
    private static final String TAG = "McpKeepAliveService";
    private static final String NOTIFY_CHAN_MCP = "MCP server";
    private static final int NOTIFY_ID_MCP = 12;

    private PowerManager.WakeLock mWakeLock;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        super.onCreate();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        acquireWakeLock();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFY_ID_MCP, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIFY_ID_MCP, buildNotification());

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireWakeLock() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":mcp_keepalive");
            mWakeLock.setReferenceCounted(false);
        }
        if (!mWakeLock.isHeld())
            mWakeLock.acquire();
    }

    private void releaseWakeLock() {
        if ((mWakeLock != null) && mWakeLock.isHeld())
            mWakeLock.release();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(NOTIFY_CHAN_MCP) == null) {
                NotificationChannel chan = new NotificationChannel(NOTIFY_CHAN_MCP,
                        getString(R.string.mcp_server), NotificationManager.IMPORTANCE_LOW); // low: no sound
                chan.setShowBadge(false);
                nm.createNotificationChannel(chan);
            }
        }
    }

    private Notification buildNotification() {
        createChannel();

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), Utils.getIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFY_CHAN_MCP)
                .setSmallIcon(R.drawable.ic_logo)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentTitle(getString(R.string.mcp_keepalive_title))
                .setContentText(getString(R.string.mcp_keepalive_text))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW); // see IMPORTANCE_LOW

        Log.d(TAG, "running");
        return builder.build();
    }

    /** Start the keep-alive foreground service. Safe to call multiple times. */
    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, McpKeepAliveService.class);
        try {
            ContextCompat.startForegroundService(ctx, intent);
        } catch (IllegalStateException e) {
            // e.g. ForegroundServiceStartNotAllowedException when the app is in the background
            Log.w(TAG, "Cannot start MCP keep-alive service: " + e.getLocalizedMessage());
        }
    }

    /** Stop the keep-alive foreground service. */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, McpKeepAliveService.class));
    }
}

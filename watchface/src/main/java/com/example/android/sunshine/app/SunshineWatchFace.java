/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with weather forecast. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface LIGHT_TYPEFACE = Typeface.create("sans-serif-light", Typeface.NORMAL);

    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);


    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private static final String TAG = "EngineWatchFace";
        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_TEMP_HIGH_KEY = "weather_temp_high_key";
        private static final String WEATHER_TEMP_LOW_KEY = "weather_temp_low_key";
        private static final String WEATHER_TEMP_ICON_KEY = "weather_temp_icon_key";
        String weather_temp_high;
        String weather_temp_low;
        Bitmap weather_temp_icon = null;

        private GoogleApiClient mGoogleApiClient;

        private static final String COLON_STRING = ":";
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        boolean mAmbient;

        /**
         * How often {@link #mUpdateTimeHandler} ticks in milliseconds.
         */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;


        //  Time ;
        Calendar mCalendar;
        Date mDate;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mShouldDrawColons;

        int backgroundColorInteractive;
        int backgroundColorAmbient;
        Paint mBackgroundPaint;
        Paint mLinePaint;
        Paint mTextPaint_time;
        Paint mTextPaint_time_light;
        Paint mTextPaint_date;
        Paint mTextPaint_temp;
        Paint mTextPaint_temp_light;
        Rect mTextBounds = new Rect();

        int lineWidth = 22;
        int offsetY = 20;
        int offsetX = 10;
        float mColonWidth = 0;
        SimpleDateFormat mDateFormat;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();

            backgroundColorInteractive = resources.getColor(R.color.digital_background_interactive);
            backgroundColorAmbient = resources.getColor(R.color.digital_background_ambient);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(backgroundColorInteractive);

            mTextPaint_time = createTextPaint(resources.getColor(R.color.main_text));
            mTextPaint_time_light = createTextPaint(resources.getColor(R.color.main_text), LIGHT_TYPEFACE);

            mTextPaint_date = createTextPaint(resources.getColor(R.color.second_text), LIGHT_TYPEFACE);
            mTextPaint_date.setAlpha(200);

            mTextPaint_temp = createTextPaint(resources.getColor(R.color.main_text));
            mTextPaint_temp_light = createTextPaint(resources.getColor(R.color.second_text), LIGHT_TYPEFACE);
            mTextPaint_temp_light.setAlpha(200);

            mLinePaint = createLinePaint(resources.getColor(R.color.second_text), 0.5f);


            mCalendar = Calendar.getInstance();
            mDate = new Date();

            //
            mColonWidth = mTextPaint_time.measureText(COLON_STRING) / 2;

            initFormats();

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(mConnectionCallbacks)
                    .addOnConnectionFailedListener(mOnConnectionFailedListener)
                    .build();
            mGoogleApiClient.connect();

        }

        GoogleApiClient.ConnectionCallbacks mConnectionCallbacks = new GoogleApiClient.ConnectionCallbacks() {
            @Override
            public void onConnected(Bundle bundle) {
                Log.v(TAG, "onConnected: Successfully connected to Google API client");
                Wearable.DataApi.addListener(mGoogleApiClient, mDataListener);
            }

            @Override
            public void onConnectionSuspended(int i) {
                Log.v(TAG, "onConnectionSuspended");
            }
        };

        GoogleApiClient.OnConnectionFailedListener mOnConnectionFailedListener = new GoogleApiClient.OnConnectionFailedListener() {
            @Override
            public void onConnectionFailed(ConnectionResult connectionResult) {
                Log.e(TAG, "onConnectionFailed(): Failed to connect, with result: " + connectionResult);
            }
        };


        DataApi.DataListener mDataListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {

                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        String path = event.getDataItem().getUri().getPath();
                        if (WEATHER_PATH.equals(path)) {
                            Log.v(TAG, "Data Changed for " + WEATHER_PATH);
                            try {
                                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                                String tempData = dataMapItem.getDataMap().getString(WEATHER_TEMP_HIGH_KEY);
                                if (tempData != null)
                                    weather_temp_high = tempData;
                                tempData = dataMapItem.getDataMap().getString(WEATHER_TEMP_LOW_KEY);

                                if (tempData != null)
                                    weather_temp_low = tempData;
                                Asset photo = dataMapItem.getDataMap().getAsset(WEATHER_TEMP_ICON_KEY);

                                if (photo != null)
                                    weather_temp_icon = loadBitmapFromAsset(mGoogleApiClient, photo);

                            } catch (Exception e) {
                                Log.e(TAG, "Exception   ", e);
                                weather_temp_icon = null;
                                weather_temp_high= null;
                                weather_temp_low = null;
                            }

                        } else {
                            Log.e(TAG, "Unrecognized path:  \"" + path + "\"");
                        }

                    }
                }
            }

            /**
             * Extracts {@link android.graphics.Bitmap} data from the
             * {@link com.google.android.gms.wearable.Asset}
             */
            private Bitmap loadBitmapFromAsset(GoogleApiClient apiClient, Asset asset) {
                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(apiClient, asset).await().getInputStream();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                return BitmapFactory.decodeStream(assetInputStream);
            }

        };




        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            return createTextPaint(textColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLinePaint(int textColor, float strokeWidth) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setStrokeWidth(strokeWidth);
            paint.setAntiAlias(true);
            return paint;
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("ccc, MMM d yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }


        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;
            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }


        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = SunshineWatchFace.this.getResources();

            mTextPaint_time_light.setTextSize(resources.getDimension(R.dimen.time_text_size));
            mTextPaint_time.setTextSize(resources.getDimension(R.dimen.time_text_size));
            mTextPaint_date.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mTextPaint_temp_light.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            mTextPaint_temp.setTextSize(resources.getDimension(R.dimen.temp_text_size));

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {

                mBackgroundPaint.setColor(inAmbientMode ? backgroundColorAmbient : backgroundColorInteractive);

                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint_time.setAntiAlias(!inAmbientMode);
                    mTextPaint_time_light.setAntiAlias(!inAmbientMode);
                    mTextPaint_date.setAntiAlias(!inAmbientMode);
                    mTextPaint_temp.setAntiAlias(!inAmbientMode);
                    mTextPaint_temp_light.setAntiAlias(!inAmbientMode);
                    mLinePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // update time
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            int centerX = bounds.width() / 2;
            int centerY = bounds.height() / 2;

            float offsetX_tmp;
            float offsetY_tmp;
            String text;
            String text2;

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (now % 1000) < 500;

            // draw date
            text = mDateFormat.format(mDate).toUpperCase();
            mTextPaint_date.getTextBounds(text, 0, text.length(), mTextBounds);
            if (!mAmbient)
                canvas.drawText(text, centerX - mTextBounds.width() / 2, centerY, mTextPaint_date);
            offsetY_tmp = mTextBounds.height();


            // draw time (hour)
            text = String.format("%02d", mCalendar.get(Calendar.HOUR_OF_DAY));
            offsetX_tmp = (int) mTextPaint_time.measureText(text + COLON_STRING);

            // blinking Colons
            if (!mAmbient && mShouldDrawColons) text = text + COLON_STRING;

            // draw time (minute)
            text2 = String.format("%02d", mCalendar.get(Calendar.MINUTE));
            mTextPaint_time_light.getTextBounds(text2, 0, text2.length(), mTextBounds);
            offsetX_tmp = offsetX_tmp + mTextBounds.width();

            canvas.drawText(text,
                    centerX - offsetX_tmp / 2,
                    centerY - offsetY + 4 - offsetY_tmp,
                    mTextPaint_time);
            canvas.drawText(text2,
                    centerX + ((offsetX_tmp / 2) - mTextBounds.width()),
                    centerY - offsetY + 4 - offsetY_tmp,
                    mTextPaint_time_light);


            if (!mAmbient) {
                // draw line
                offsetY_tmp = offsetY;
                canvas.drawLine(centerX - lineWidth, centerY + offsetY, centerX + lineWidth, centerY + offsetY_tmp, mLinePaint);

                if (weather_temp_high != null && weather_temp_low != null) {
                    // draw temperature high
                    mTextPaint_temp.getTextBounds(weather_temp_high, 0, weather_temp_high.length(), mTextBounds);
                    offsetY_tmp = mTextBounds.height() + offsetY + offsetY_tmp;
                    canvas.drawText(weather_temp_high, centerX - mTextBounds.width() / 2, centerY + offsetY_tmp, mTextPaint_temp);

                    // draw temperature low
                    canvas.drawText(weather_temp_low, centerX + mTextBounds.width() / 2 + offsetX, centerY + offsetY_tmp, mTextPaint_temp_light);

                    if (weather_temp_icon != null) {
                        // draw weather icon
                        canvas.drawBitmap(weather_temp_icon,
                                centerX - mTextBounds.width() / 2 - offsetX - weather_temp_icon.getWidth(),
                                centerY + offsetY_tmp - weather_temp_icon.getHeight() / 2 - mTextBounds.height() / 2, null);
                    }
                } else {
                    // draw temperature high
                    text = getString(R.string.no_weather_info);
                    mTextPaint_date.getTextBounds(text, 0, text.length(), mTextBounds);
                    offsetY_tmp = mTextBounds.height() + offsetY + offsetY_tmp;
                    canvas.drawText(text, centerX - mTextBounds.width() / 2, centerY + offsetY_tmp, mTextPaint_date);

                }
            }
        }


        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }


}

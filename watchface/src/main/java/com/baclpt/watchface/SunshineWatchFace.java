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

package com.baclpt.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
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
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

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
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
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

        //  Time ;
        Calendar mCalendar;
        Date mDate;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        int backgroundColorInteractive;
        int backgroundColorAmbient;
        Paint mBackgroundPaint;
        Paint mLinePaint;
        Paint mTextPaint_time;
        Paint mTextPaint_time_light;
        Paint mTextPaint_date;
        Paint mTextPaint_temp;
        Paint mTextPaint_temp_light;
        Bitmap mWeatherIcon;
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

            mTextPaint_temp = createTextPaint(resources.getColor(R.color.main_text));
            mTextPaint_temp_light = createTextPaint(resources.getColor(R.color.second_text), LIGHT_TYPEFACE);

            mLinePaint = createLinePaint(resources.getColor(R.color.second_text), 0.5f);

            mWeatherIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_clear);

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            //
            mColonWidth = mTextPaint_time.measureText(COLON_STRING) / 2;

            initFormats();


        }

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


            // draw date
            text = mDateFormat.format(mDate).toUpperCase();
            mTextPaint_date.getTextBounds(text, 0, text.length(), mTextBounds);
            if (!mAmbient)
                canvas.drawText(text, centerX - mTextBounds.width() / 2, centerY, mTextPaint_date);
            offsetY_tmp = mTextBounds.height();

            // draw time
            text = mAmbient ? String.format("%02d ", mCalendar.get(Calendar.HOUR_OF_DAY)) : String.format("%02d:", mCalendar.get(Calendar.HOUR_OF_DAY));
            offsetX_tmp = (int) mTextPaint_time.measureText(text);

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


                // draw temperature h
                text = "25" + (char) 0x00B0;
                mTextPaint_temp.getTextBounds(text, 0, text.length(), mTextBounds);
                offsetY_tmp = mTextBounds.height() + offsetY + offsetY_tmp;
                canvas.drawText(text, centerX - mTextBounds.width() / 2, centerY + offsetY_tmp, mTextPaint_temp);

                // draw temperature low
                text = "16" + (char) 0x00B0;
                canvas.drawText(text, centerX + mTextBounds.width() / 2 + offsetX, centerY + offsetY_tmp, mTextPaint_temp_light);


                // draw weather icon
                canvas.drawBitmap(mWeatherIcon,
                        centerX - mTextBounds.width() / 2 - offsetX - mWeatherIcon.getWidth(),
                        centerY + offsetY_tmp - mWeatherIcon.getHeight() / 2 - mTextBounds.height() / 2, null);
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
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
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

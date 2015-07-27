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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

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
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mLinePaint;
        Paint mTextPaint_time;
        Paint mTextPaint_time_bold;
        Paint mTextPaint_date;
        Paint mTextPaint_temp;
        Paint mTextPaint_temp_bold;

        Bitmap mWeatherIcon;

        Rect mTextBounds = new Rect();

        boolean mAmbient;

        Time mTime;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));


            mTextPaint_time = new Paint();
            mTextPaint_time = createTextPaint(resources.getColor(R.color.main_text), NORMAL_TYPEFACE);
            //TODO: mudar isto
            mTextPaint_time_bold = new Paint();
            mTextPaint_time_bold = createTextPaint(resources.getColor(R.color.main_text), NORMAL_TYPEFACE);

            mTextPaint_date = new Paint();
            mTextPaint_date = createTextPaint(resources.getColor(R.color.second_text), NORMAL_TYPEFACE);

            mTextPaint_temp = new Paint();
            mTextPaint_temp = createTextPaint(resources.getColor(R.color.second_text), NORMAL_TYPEFACE);
            mTextPaint_temp_bold = new Paint();
            mTextPaint_temp_bold = createTextPaint(resources.getColor(R.color.main_text), NORMAL_TYPEFACE);

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.second_text));
            mLinePaint.setStrokeWidth(0.5f);
            mLinePaint.setAntiAlias(true);

            mWeatherIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_clear);

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
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

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint_time.setTextSize(resources.getDimension(R.dimen.time_text_size));
            mTextPaint_time_bold.setTextSize(resources.getDimension(R.dimen.time_text_size));
            mTextPaint_date.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mTextPaint_temp.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            mTextPaint_temp_bold.setTextSize(resources.getDimension(R.dimen.temp_text_size));

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
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint_time.setAntiAlias(!inAmbientMode);
                    mTextPaint_date.setAntiAlias(!inAmbientMode);
                    mTextPaint_temp.setAntiAlias(!inAmbientMode);
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

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
//            String text = mAmbient
//                    ? String.format("%d %02d", mTime.hour, mTime.minute)
//                    : String.format("%d:%02d", mTime.hour, mTime.minute);

            // canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            int espacamentoY = 20;
            int espacamentoX = 10;
            int espacamentoY_tmp = 0;
            String text;
            int centerX = bounds.width() / 2;
            int centerY = bounds.height() / 2;


            // draw date
            text = "FRI, JUL 14 2015";
            mTextPaint_date.getTextBounds(text, 0, text.length(), mTextBounds);
            canvas.drawText(text, centerX - mTextBounds.width() / 2, centerY, mTextPaint_date);
            espacamentoY_tmp = mTextBounds.height();

            // draw time
            text = "15:50";
            mTextPaint_time_bold.getTextBounds(text, 0, text.length(), mTextBounds);
            canvas.drawText(text, centerX - mTextBounds.width() / 2, centerY - espacamentoY + 4 - espacamentoY_tmp, mTextPaint_time_bold);

            // draw line
            espacamentoY_tmp = espacamentoY;
            canvas.drawLine(centerX - 20, centerY + espacamentoY, centerX + 20, centerY + espacamentoY_tmp, mLinePaint);


            // draw temperature h
            text = "25"+ (char) 0x00B0 ;
            mTextPaint_temp_bold.getTextBounds(text, 0, text.length(), mTextBounds);
            espacamentoY_tmp = mTextBounds.height() + espacamentoY + espacamentoY_tmp;
            canvas.drawText(text, centerX - mTextBounds.width() / 2, centerY + espacamentoY_tmp, mTextPaint_temp_bold);

            // draw temperature low
            text = "16"+ (char) 0x00B0 ;
            canvas.drawText(text, centerX + mTextBounds.width() / 2 + espacamentoX, centerY + espacamentoY_tmp, mTextPaint_temp);


            // draw weather icon
            canvas.drawBitmap(mWeatherIcon,
                    centerX - mTextBounds.width() / 2 - espacamentoX - mWeatherIcon.getWidth(),
                    centerY + espacamentoY_tmp - mWeatherIcon.getHeight()/2 -  mTextBounds.height() / 2, null);
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

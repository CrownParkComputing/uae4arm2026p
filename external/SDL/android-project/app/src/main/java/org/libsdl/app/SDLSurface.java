package org.libsdl.app;


import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;


/**
    SDLSurface. This is what we draw on, so we need to know when it's created
    in order to do anything useful.

    Because of this, that's where we set up the SDL thread
*/
public class SDLSurface extends SurfaceView implements SurfaceHolder.Callback,
    View.OnKeyListener, View.OnTouchListener, SensorEventListener  {

    // Touch-to-mouse mapping (Amiberry-specific UX):
    // - 1 finger moves the mouse
    // - tap = left click
    // - 2nd finger down = hold right button (Workbench menus)
    // - release always sends button-up to avoid "stuck click"
    private static final boolean ENABLE_TOUCH_MOUSE = true;
    private static final int TAP_TIMEOUT_MS = 450;
    private static final int LONG_PRESS_MS = 350;

    private float mTouchSlopPx = 20.0f;

    private int mPrimaryPointerId = -1;
    private int mSecondaryPointerId = -1;
    private long mPrimaryDownTimeMs = 0;
    private float mPrimaryDownX = 0;
    private float mPrimaryDownY = 0;
    private float mLastTouchX = 0;
    private float mLastTouchY = 0;
    private float mLastSentX = 0;
    private float mLastSentY = 0;
    private float mVirtualMouseX = 0;
    private float mVirtualMouseY = 0;
    private boolean mHasVirtualMousePos = false;
    private boolean mPrimaryMoved = false;
    private boolean mLeftHeld = false;
    private boolean mRightHeld = false;

    // Sensors
    protected SensorManager mSensorManager;
    protected Display mDisplay;

    // Keep track of the surface size to normalize touch events
    protected float mWidth, mHeight;

    // Is SurfaceView ready for rendering
    public boolean mIsSurfaceReady;

    // Startup
    public SDLSurface(Context context) {
        super(context);
        getHolder().addCallback(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);

        mDisplay = ((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);

        setOnGenericMotionListener(SDLActivity.getMotionListener());

        // Some arbitrary defaults to avoid a potential division by zero
        mWidth = 1.0f;
        mHeight = 1.0f;

        // Tap-vs-drag detection: use system touch slop (scaled for the device)
        // and be a bit forgiving for emulator use.
        try {
            mTouchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop() * 1.5f;
        } catch (Exception ignored) {
            try {
                final float density = getResources().getDisplayMetrics().density;
                mTouchSlopPx = 16.0f * (density > 0 ? density : 1.0f);
            } catch (Exception ignored2) {
                mTouchSlopPx = 24.0f;
            }
        }

        mIsSurfaceReady = false;
    }

    public void handlePause() {
        enableSensor(Sensor.TYPE_ACCELEROMETER, false);
    }

    public void handleResume() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);
        enableSensor(Sensor.TYPE_ACCELEROMETER, true);
    }

    public Surface getNativeSurface() {
        return getHolder().getSurface();
    }

    // Called when we have a valid drawing surface
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v("SDL", "surfaceCreated()");
        SDLActivity.onNativeSurfaceCreated();
    }

    // Called when we lose the surface
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v("SDL", "surfaceDestroyed()");

        // Transition to pause, if needed
        SDLActivity.mNextNativeState = SDLActivity.NativeState.PAUSED;
        SDLActivity.handleNativeState();

        mIsSurfaceReady = false;
        SDLActivity.onNativeSurfaceDestroyed();
    }

    // Called when the surface is resized
    @Override
    public void surfaceChanged(SurfaceHolder holder,
                               int format, int width, int height) {
        Log.v("SDL", "surfaceChanged()");

        if (SDLActivity.mSingleton == null) {
            return;
        }

        mWidth = width;
        mHeight = height;
        int nDeviceWidth = width;
        int nDeviceHeight = height;
        try
        {
            if (Build.VERSION.SDK_INT >= 17 /* Android 4.2 (JELLY_BEAN_MR1) */) {
                DisplayMetrics realMetrics = new DisplayMetrics();
                mDisplay.getRealMetrics( realMetrics );
                nDeviceWidth = realMetrics.widthPixels;
                nDeviceHeight = realMetrics.heightPixels;
            }
        } catch(Exception ignored) {
        }

        synchronized(SDLActivity.getContext()) {
            // In case we're waiting on a size change after going fullscreen, send a notification.
            SDLActivity.getContext().notifyAll();
        }

        Log.v("SDL", "Window size: " + width + "x" + height);
        Log.v("SDL", "Device size: " + nDeviceWidth + "x" + nDeviceHeight);
        SDLActivity.nativeSetScreenResolution(width, height, nDeviceWidth, nDeviceHeight, mDisplay.getRefreshRate());
        SDLActivity.onNativeResize();

        // Prevent a screen distortion glitch,
        // for instance when the device is in Landscape and a Portrait App is resumed.
        boolean skip = false;
        int requestedOrientation = SDLActivity.mSingleton.getRequestedOrientation();

        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
            if (mWidth > mHeight) {
               skip = true;
            }
        } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            if (mWidth < mHeight) {
               skip = true;
            }
        }

        // Special Patch for Square Resolution: Black Berry Passport
        if (skip) {
           double min = Math.min(mWidth, mHeight);
           double max = Math.max(mWidth, mHeight);

           if (max / min < 1.20) {
              Log.v("SDL", "Don't skip on such aspect-ratio. Could be a square resolution.");
              skip = false;
           }
        }

        // Don't skip in MultiWindow.
        if (skip) {
            if (Build.VERSION.SDK_INT >= 24 /* Android 7.0 (N) */) {
                if (SDLActivity.mSingleton.isInMultiWindowMode()) {
                    Log.v("SDL", "Don't skip in Multi-Window");
                    skip = false;
                }
            }
        }

        if (skip) {
           Log.v("SDL", "Skip .. Surface is not ready.");
           mIsSurfaceReady = false;
           return;
        }

        /* If the surface has been previously destroyed by onNativeSurfaceDestroyed, recreate it here */
        SDLActivity.onNativeSurfaceChanged();

        /* Surface is ready */
        mIsSurfaceReady = true;

        SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
        SDLActivity.handleNativeState();
    }

    // Key events
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return SDLActivity.handleKeyEvent(v, keyCode, event, null);
    }

    // Touch events
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        /* Ref: http://developer.android.com/training/gestures/multi.html */
        int touchDevId = event.getDeviceId();
        final int pointerCount = event.getPointerCount();
        int action = event.getActionMasked();
        int pointerFingerId;
        int i = -1;
        float x,y,p;

        /*
         * Prevent id to be -1, since it's used in SDL internal for synthetic events
         * Appears when using Android emulator, eg:
         *  adb shell input mouse tap 100 100
         *  adb shell input touchscreen tap 100 100
         */
        if (touchDevId < 0) {
            touchDevId -= 1;
        }

        // 12290 = Samsung DeX mode desktop mouse
        // 12290 = 0x3002 = 0x2002 | 0x1002 = SOURCE_MOUSE | SOURCE_TOUCHSCREEN
        // 0x2   = SOURCE_CLASS_POINTER
        if (event.getSource() == InputDevice.SOURCE_MOUSE || event.getSource() == (InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_TOUCHSCREEN)) {
            int mouseButton = 1;
            try {
                Object object = event.getClass().getMethod("getButtonState").invoke(event);
                if (object != null) {
                    mouseButton = (Integer) object;
                }
            } catch(Exception ignored) {
            }

            // We need to check if we're in relative mouse mode and get the axis offset rather than the x/y values
            // if we are.  We'll leverage our existing mouse motion listener
            SDLGenericMotionListener_API12 motionListener = SDLActivity.getMotionListener();
            x = motionListener.getEventX(event);
            y = motionListener.getEventY(event);

            // Some devices/reporting paths deliver mouse coordinates in view-space, while SDL's
            // window/surface buffer is smaller (or scaled differently). Scale absolute coords
            // into surface-space so the pointer can reach the full emulated area.
            if (!motionListener.inRelativeMode()) {
                final float viewW = (v != null && v.getWidth() > 0) ? (float) v.getWidth() : mWidth;
                final float viewH = (v != null && v.getHeight() > 0) ? (float) v.getHeight() : mHeight;
                final float sx = (viewW > 0.0f && mWidth > 0.0f) ? (mWidth / viewW) : 1.0f;
                final float sy = (viewH > 0.0f && mHeight > 0.0f) ? (mHeight / viewH) : 1.0f;
                x = x * sx;
                y = y * sy;
            }

            SDLActivity.onNativeMouse(mouseButton, action, x, y, motionListener.inRelativeMode());
        } else {
            // For touchscreen input, use an explicit touch->mouse mapping for emulator UX.
            // SDL's default touch pipeline can result in "stuck" mouse buttons in some apps.
            if (ENABLE_TOUCH_MOUSE && (event.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
                final int primaryButton = MotionEvent.BUTTON_PRIMARY;
                final int secondaryButton = MotionEvent.BUTTON_SECONDARY;

                // On some devices/paths the Surface buffer (mWidth/mHeight) can be smaller than the
                // fullscreen view size (v.getWidth()/v.getHeight()). If we add raw finger deltas in
                // view pixels into a virtual mouse tracked in surface pixels, the cursor clamps early
                // (e.g., only reaches ~2/3 across). Scale view-space movement into surface-space.
                final float viewW = (v != null && v.getWidth() > 0) ? (float) v.getWidth() : mWidth;
                final float viewH = (v != null && v.getHeight() > 0) ? (float) v.getHeight() : mHeight;
                final float scaleX = (viewW > 0.0f && mWidth > 0.0f) ? (mWidth / viewW) : 1.0f;
                final float scaleY = (viewH > 0.0f && mHeight > 0.0f) ? (mHeight / viewH) : 1.0f;

                // Helper to release any held buttons to avoid permanent click state.
                final Runnable releaseButtons = () -> {
                    if (mLeftHeld) {
                        SDLActivity.onNativeMouse(primaryButton, MotionEvent.ACTION_UP, mVirtualMouseX, mVirtualMouseY, false);
                        mLeftHeld = false;
                    }
                    if (mRightHeld) {
                        SDLActivity.onNativeMouse(secondaryButton, MotionEvent.ACTION_UP, mVirtualMouseX, mVirtualMouseY, false);
                        mRightHeld = false;
                        mSecondaryPointerId = -1;
                    }
                };

                switch (action) {
                    case MotionEvent.ACTION_DOWN: {
                        mPrimaryPointerId = event.getPointerId(0);
                        mSecondaryPointerId = -1;
                        mPrimaryDownTimeMs = event.getEventTime();
                        mPrimaryDownX = event.getX(0);
                        mPrimaryDownY = event.getY(0);
                        mLastTouchX = mPrimaryDownX;
                        mLastTouchY = mPrimaryDownY;
                        if (!mHasVirtualMousePos) {
                            // Start from center on first touch; afterward we keep the last cursor position.
                            mVirtualMouseX = mWidth / 2.0f;
                            mVirtualMouseY = mHeight / 2.0f;
                            mHasVirtualMousePos = true;
                        }
                        mLastSentX = mVirtualMouseX;
                        mLastSentY = mVirtualMouseY;
                        mPrimaryMoved = false;
                        mLeftHeld = false;
                        mRightHeld = false;

                        // Defensive: clear any stale button state inside SDL's Android mouse backend.
                        // (If its last_state is stuck, ACTION_DOWN might not generate a press.)
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, mVirtualMouseX, mVirtualMouseY, false);

                        // Do NOT move the mouse on touch-down; only drag should move it.
                        return true;
                    }

                    case MotionEvent.ACTION_POINTER_DOWN: {
                        // When the 2nd finger goes down: hold right mouse button.
                        if (event.getPointerCount() >= 2 && !mRightHeld) {
                            int idx = event.getActionIndex();
                            mSecondaryPointerId = event.getPointerId(idx);
                            mRightHeld = true;
                            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, mVirtualMouseX, mVirtualMouseY, false);
                            mLastSentX = mVirtualMouseX;
                            mLastSentY = mVirtualMouseY;
                            SDLActivity.onNativeMouse(secondaryButton, MotionEvent.ACTION_DOWN, mVirtualMouseX, mVirtualMouseY, false);
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_MOVE: {
                        int primaryIndex = event.findPointerIndex(mPrimaryPointerId);
                        if (primaryIndex < 0) primaryIndex = 0;

                        float x0 = event.getX(primaryIndex);
                        float y0 = event.getY(primaryIndex);

                        // Update last-touch deltas for relative mouse movement.
                        float dtx = x0 - mLastTouchX;
                        float dty = y0 - mLastTouchY;
                        mLastTouchX = x0;
                        mLastTouchY = y0;

                        float dx = x0 - mPrimaryDownX;
                        float dy = y0 - mPrimaryDownY;
                        float dist2 = dx * dx + dy * dy;
                        float moveStartSlop = mTouchSlopPx * 2.0f;
                        float moveStartSlop2 = moveStartSlop * moveStartSlop;
                        if (!mPrimaryMoved) {
                            // Ignore tiny jitter while the user is trying to tap.
                            if (dist2 <= moveStartSlop2) {
                                // Still allow long-press detection below.
                            } else {
                                mPrimaryMoved = true;
                            }
                        }

                        // Long-press to allow left-button hold/drag (useful in games)
                        if (!mLeftHeld && !mRightHeld && !mPrimaryMoved) {
                            long heldMs = event.getEventTime() - mPrimaryDownTimeMs;
                            if (heldMs >= LONG_PRESS_MS) {
                                mLeftHeld = true;
                                // Clear state before pressing to guarantee a delta.
                                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, mVirtualMouseX, mVirtualMouseY, false);
                                SDLActivity.onNativeMouse(primaryButton, MotionEvent.ACTION_DOWN, mVirtualMouseX, mVirtualMouseY, false);
                            }
                        }

                        // Only move the pointer if the user is intentionally moving (past slop)
                        // or actively dragging (left/right held).
                        if (mPrimaryMoved || mLeftHeld || mRightHeld) {
                            // Relative move: do not warp the cursor to the finger position.
                            mVirtualMouseX += dtx * scaleX;
                            mVirtualMouseY += dty * scaleY;
                            if (mVirtualMouseX < 0) mVirtualMouseX = 0;
                            if (mVirtualMouseY < 0) mVirtualMouseY = 0;
                            // Clamp to valid surface bounds.
                            if (mVirtualMouseX > (mWidth - 1.0f)) mVirtualMouseX = (mWidth - 1.0f);
                            if (mVirtualMouseY > (mHeight - 1.0f)) mVirtualMouseY = (mHeight - 1.0f);

                            float mdx = Math.abs(mVirtualMouseX - mLastSentX);
                            float mdy = Math.abs(mVirtualMouseY - mLastSentY);
                            if (mdx >= 1.0f || mdy >= 1.0f) {
                                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, mVirtualMouseX, mVirtualMouseY, false);
                                mLastSentX = mVirtualMouseX;
                                mLastSentY = mVirtualMouseY;
                            }
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_POINTER_UP: {
                        int idx = event.getActionIndex();
                        int pid = event.getPointerId(idx);

                        // If the secondary finger lifts, release right button.
                        if (mRightHeld && pid == mSecondaryPointerId) {
                            SDLActivity.onNativeMouse(secondaryButton, MotionEvent.ACTION_UP, mVirtualMouseX, mVirtualMouseY, false);
                            mRightHeld = false;
                            mSecondaryPointerId = -1;
                        }

                        // If primary lifted but another finger remains, promote it to primary.
                        if (pid == mPrimaryPointerId) {
                            for (int k = 0; k < event.getPointerCount(); k++) {
                                int otherId = event.getPointerId(k);
                                if (otherId != pid) {
                                    mPrimaryPointerId = otherId;
                                    mPrimaryDownX = event.getX(k);
                                    mPrimaryDownY = event.getY(k);
                                    mLastTouchX = mPrimaryDownX;
                                    mLastTouchY = mPrimaryDownY;
                                    mPrimaryDownTimeMs = event.getEventTime();
                                    mPrimaryMoved = false;
                                    break;
                                }
                            }
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP: {
                        int primaryIndex = event.findPointerIndex(mPrimaryPointerId);
                        if (primaryIndex < 0) primaryIndex = 0;
                        float x0 = event.getX(primaryIndex);
                        float y0 = event.getY(primaryIndex);

                        // In relative mode, taps/clicks should happen at the current cursor position.
                        final float tapX = mVirtualMouseX;
                        final float tapY = mVirtualMouseY;

                        // Release any held buttons first.
                        if (mRightHeld) {
                            SDLActivity.onNativeMouse(secondaryButton, MotionEvent.ACTION_UP, tapX, tapY, false);
                            mRightHeld = false;
                        }

                        if (mLeftHeld) {
                            SDLActivity.onNativeMouse(primaryButton, MotionEvent.ACTION_UP, tapX, tapY, false);
                            mLeftHeld = false;
                        } else {
                            long dt = event.getEventTime() - mPrimaryDownTimeMs;
                            float dx = x0 - mPrimaryDownX;
                            float dy = y0 - mPrimaryDownY;
                            float dist2 = dx * dx + dy * dy;
                            float tapSlop2 = (mTouchSlopPx * 2.0f) * (mTouchSlopPx * 2.0f);
                            boolean isTap = (dt <= TAP_TIMEOUT_MS) && (dist2 <= tapSlop2);
                            if (isTap) {
                                // Tap = left click
                                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, tapX, tapY, false);
                                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, tapX, tapY, false);
                                mLastSentX = tapX;
                                mLastSentY = tapY;
                                SDLActivity.onNativeMouse(primaryButton, MotionEvent.ACTION_DOWN, tapX, tapY, false);
                                SDLActivity.onNativeMouse(primaryButton, MotionEvent.ACTION_UP, tapX, tapY, false);
                            }
                        }

                        mPrimaryPointerId = -1;
                        mSecondaryPointerId = -1;
                        mPrimaryMoved = false;
                        return true;
                    }

                    case MotionEvent.ACTION_CANCEL: {
                        releaseButtons.run();
                        mPrimaryPointerId = -1;
                        mSecondaryPointerId = -1;
                        mPrimaryMoved = false;
                        return true;
                    }

                    default:
                        return true;
                }
            }

            switch(action) {
                case MotionEvent.ACTION_MOVE:
                    for (i = 0; i < pointerCount; i++) {
                        pointerFingerId = event.getPointerId(i);
                        x = event.getX(i) / mWidth;
                        y = event.getY(i) / mHeight;
                        p = event.getPressure(i);
                        if (p > 1.0f) {
                            // may be larger than 1.0f on some devices
                            // see the documentation of getPressure(i)
                            p = 1.0f;
                        }
                        SDLActivity.onNativeTouch(touchDevId, pointerFingerId, action, x, y, p);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_DOWN:
                    // Primary pointer up/down, the index is always zero
                    i = 0;
                    /* fallthrough */
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_POINTER_DOWN:
                    // Non primary pointer up/down
                    if (i == -1) {
                        i = event.getActionIndex();
                    }

                    pointerFingerId = event.getPointerId(i);
                    x = event.getX(i) / mWidth;
                    y = event.getY(i) / mHeight;
                    p = event.getPressure(i);
                    if (p > 1.0f) {
                        // may be larger than 1.0f on some devices
                        // see the documentation of getPressure(i)
                        p = 1.0f;
                    }
                    SDLActivity.onNativeTouch(touchDevId, pointerFingerId, action, x, y, p);
                    break;

                case MotionEvent.ACTION_CANCEL:
                    for (i = 0; i < pointerCount; i++) {
                        pointerFingerId = event.getPointerId(i);
                        x = event.getX(i) / mWidth;
                        y = event.getY(i) / mHeight;
                        p = event.getPressure(i);
                        if (p > 1.0f) {
                            // may be larger than 1.0f on some devices
                            // see the documentation of getPressure(i)
                            p = 1.0f;
                        }
                        SDLActivity.onNativeTouch(touchDevId, pointerFingerId, MotionEvent.ACTION_UP, x, y, p);
                    }
                    break;

                default:
                    break;
            }
        }

        return true;
   }

    // Sensor events
    public void enableSensor(int sensortype, boolean enabled) {
        // TODO: This uses getDefaultSensor - what if we have >1 accels?
        if (enabled) {
            mSensorManager.registerListener(this,
                            mSensorManager.getDefaultSensor(sensortype),
                            SensorManager.SENSOR_DELAY_GAME, null);
        } else {
            mSensorManager.unregisterListener(this,
                            mSensorManager.getDefaultSensor(sensortype));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            // Since we may have an orientation set, we won't receive onConfigurationChanged events.
            // We thus should check here.
            int newOrientation;

            float x, y;
            switch (mDisplay.getRotation()) {
                case Surface.ROTATION_90:
                    x = -event.values[1];
                    y = event.values[0];
                    newOrientation = SDLActivity.SDL_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_270:
                    x = event.values[1];
                    y = -event.values[0];
                    newOrientation = SDLActivity.SDL_ORIENTATION_LANDSCAPE_FLIPPED;
                    break;
                case Surface.ROTATION_180:
                    x = -event.values[0];
                    y = -event.values[1];
                    newOrientation = SDLActivity.SDL_ORIENTATION_PORTRAIT_FLIPPED;
                    break;
                case Surface.ROTATION_0:
                default:
                    x = event.values[0];
                    y = event.values[1];
                    newOrientation = SDLActivity.SDL_ORIENTATION_PORTRAIT;
                    break;
            }

            if (newOrientation != SDLActivity.mCurrentOrientation) {
                SDLActivity.mCurrentOrientation = newOrientation;
                SDLActivity.onNativeOrientationChanged(newOrientation);
            }

            SDLActivity.onNativeAccel(-x / SensorManager.GRAVITY_EARTH,
                                      y / SensorManager.GRAVITY_EARTH,
                                      event.values[2] / SensorManager.GRAVITY_EARTH);


        }
    }

    // Captured pointer events for API 26.
    public boolean onCapturedPointerEvent(MotionEvent event)
    {
        int action = event.getActionMasked();

        float x, y;
        switch (action) {
            case MotionEvent.ACTION_SCROLL:
                x = event.getAxisValue(MotionEvent.AXIS_HSCROLL, 0);
                y = event.getAxisValue(MotionEvent.AXIS_VSCROLL, 0);
                SDLActivity.onNativeMouse(0, action, x, y, false);
                return true;

            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
                x = event.getX(0);
                y = event.getY(0);
                SDLActivity.onNativeMouse(0, action, x, y, true);
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:

                // Change our action value to what SDL's code expects.
                if (action == MotionEvent.ACTION_BUTTON_PRESS) {
                    action = MotionEvent.ACTION_DOWN;
                } else { /* MotionEvent.ACTION_BUTTON_RELEASE */
                    action = MotionEvent.ACTION_UP;
                }

                x = event.getX(0);
                y = event.getY(0);
                int button = event.getButtonState();

                SDLActivity.onNativeMouse(button, action, x, y, true);
                return true;
        }

        return false;
    }
}

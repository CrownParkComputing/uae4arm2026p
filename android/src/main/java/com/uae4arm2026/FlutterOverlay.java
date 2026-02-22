package com.uae4arm2026;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Optional Flutter overlay host.
 *
 * This class uses reflection so the Android app can still build/run without
 * Flutter being present. If Flutter AARs are added to the Gradle build, it
 * will embed a FlutterView into the provided container.
 */
public final class FlutterOverlay {

    private static final String TAG = "FlutterOverlay";

    private Object flutterEngine;
    private View flutterView;

    public boolean isAttached() {
        return flutterView != null;
    }

    public boolean attachIfAvailable(Activity activity, ViewGroup container) {
        if (activity == null || container == null) {
            return false;
        }
        if (isAttached()) {
            return true;
        }

        try {
            // Classes (Flutter embedding v2)
            Class<?> flutterEngineClass = Class.forName("io.flutter.embedding.engine.FlutterEngine");
            Class<?> flutterViewClass = Class.forName("io.flutter.embedding.android.FlutterView");
            Class<?> dartExecutorClass = Class.forName("io.flutter.embedding.engine.dart.DartExecutor");
            Class<?> dartEntrypointClass = Class.forName("io.flutter.embedding.engine.dart.DartExecutor$DartEntrypoint");

            // new FlutterEngine(Context)
            Constructor<?> engineCtor = flutterEngineClass.getConstructor(Context.class);
            flutterEngine = engineCtor.newInstance(activity);

            // If the embedding includes plugins, register them so MethodChannels work.
            // This is optional and safe to skip when GeneratedPluginRegistrant isn't present.
            try {
                Class<?> registrantClass = Class.forName("io.flutter.plugins.GeneratedPluginRegistrant");
                Method registerWith = registrantClass.getMethod("registerWith", flutterEngineClass);
                registerWith.invoke(null, flutterEngine);
                LogUtil.i(TAG, "GeneratedPluginRegistrant.registerWith() applied");
            } catch (Throwable ignored) {
                // No-op: plugin registrant not present.
            }

            // engine.getDartExecutor()
            Method getDartExecutor = flutterEngineClass.getMethod("getDartExecutor");
            Object dartExecutor = getDartExecutor.invoke(flutterEngine);

            // DartEntrypoint.createDefault()
            Method createDefault = dartEntrypointClass.getMethod("createDefault");
            Object entrypoint = createDefault.invoke(null);

            // dartExecutor.executeDartEntrypoint(entrypoint)
            Method executeEntrypoint = dartExecutorClass.getMethod("executeDartEntrypoint", dartEntrypointClass);
            executeEntrypoint.invoke(dartExecutor, entrypoint);

            // new FlutterView(Context)
            Constructor<?> viewCtor = flutterViewClass.getConstructor(Context.class);
            flutterView = (View) viewCtor.newInstance(activity);

            // flutterView.attachToFlutterEngine(engine)
            Method attachToEngine = flutterViewClass.getMethod("attachToFlutterEngine", flutterEngineClass);
            attachToEngine.invoke(flutterView, flutterEngine);

            container.addView(flutterView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            container.bringToFront();

            LogUtil.i(TAG, "Flutter overlay attached");
            return true;
        } catch (ClassNotFoundException e) {
            LogUtil.i(TAG, "Flutter classes not found; overlay disabled");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to attach Flutter overlay: " + t);
            return false;
        }
    }

    public void detach(ViewGroup container) {
        try {
            if (container != null && flutterView != null) {
                container.removeView(flutterView);
            }
        } catch (Throwable ignored) {
        }

        // Detach view from engine if possible.
        try {
            if (flutterView != null && flutterEngine != null) {
                Class<?> flutterEngineClass = Class.forName("io.flutter.embedding.engine.FlutterEngine");
                Class<?> flutterViewClass = Class.forName("io.flutter.embedding.android.FlutterView");
                Method detachFromEngine = flutterViewClass.getMethod("detachFromFlutterEngine");
                detachFromEngine.invoke(flutterView);

                Method destroy = flutterEngineClass.getMethod("destroy");
                destroy.invoke(flutterEngine);
            }
        } catch (Throwable ignored) {
        }

        flutterView = null;
        flutterEngine = null;
    }
}

package com.uae4arm2026;

import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class BootstrapMediaSwapperRows {
    private BootstrapMediaSwapperRows() {
    }

    static void addRow(Context context, LinearLayout root, String label, Runnable onSwap,
                       Runnable beforePickerAction, Runnable afterLocalAction) {
        addRowWithEject(context, root, label, onSwap, null, beforePickerAction, afterLocalAction);
    }

    static void addRowWithEject(Context context, LinearLayout root, String label,
                                Runnable onInsert, Runnable onEject,
                                Runnable beforePickerAction, Runnable afterLocalAction) {
        if (context == null || root == null) return;
        try {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * context.getResources().getDisplayMetrics().density), 0, 0);

            TextView tv = new TextView(context);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText(label);

            Button btnInsert = new Button(context);
            btnInsert.setText("Insert");
            btnInsert.setOnClickListener(v -> {
                try {
                    if (beforePickerAction != null) beforePickerAction.run();
                } catch (Throwable ignored) {
                }
                try {
                    if (onInsert != null) onInsert.run();
                } catch (Throwable ignored) {
                }
            });

            row.addView(tv);
            row.addView(btnInsert);

            if (onEject != null) {
                Button btnEject = new Button(context);
                btnEject.setText("Eject");
                btnEject.setOnClickListener(v -> {
                    try {
                        onEject.run();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (afterLocalAction != null) afterLocalAction.run();
                    } catch (Throwable ignored) {
                    }
                });
                row.addView(btnEject);
            }

            root.addView(row);
        } catch (Throwable ignored) {
        }
    }

    static void addActionRow(Context context, LinearLayout root, String label, String buttonLabel,
                             Runnable onAction, Runnable beforePickerAction) {
        addActionRow(context, root, label, buttonLabel, onAction, beforePickerAction, null);
    }

    static void addActionRow(Context context, LinearLayout root, String label, String buttonLabel,
                             Runnable onAction, Runnable beforeAction, Runnable afterAction) {
        if (context == null || root == null) return;
        try {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * context.getResources().getDisplayMetrics().density), 0, 0);

            TextView tv = new TextView(context);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText(label);

            Button btn = new Button(context);
            btn.setText(buttonLabel == null ? "Select" : buttonLabel);
            btn.setOnClickListener(v -> {
                try {
                    if (beforeAction != null) beforeAction.run();
                } catch (Throwable ignored) {
                }
                try {
                    if (onAction != null) onAction.run();
                } catch (Throwable ignored) {
                }
                try {
                    if (afterAction != null) afterAction.run();
                } catch (Throwable ignored) {
                }
            });

            row.addView(tv);
            row.addView(btn);
            root.addView(row);
        } catch (Throwable ignored) {
        }
    }

    static void addRowWithClear(Context context, LinearLayout root, String label,
                                Runnable onSwap, String clearLabel, Runnable onClear,
                                Runnable beforePickerAction, Runnable afterLocalAction) {
        if (context == null || root == null) return;
        try {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * context.getResources().getDisplayMetrics().density), 0, 0);

            TextView tv = new TextView(context);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText(label);

            Button btnSwap = new Button(context);
            btnSwap.setText("Insert");
            btnSwap.setOnClickListener(v -> {
                try {
                    if (beforePickerAction != null) beforePickerAction.run();
                } catch (Throwable ignored) {
                }
                try {
                    if (onSwap != null) onSwap.run();
                } catch (Throwable ignored) {
                }
            });

            Button btnClear = new Button(context);
            btnClear.setText(clearLabel == null ? "Clear" : clearLabel);
            btnClear.setOnClickListener(v -> {
                try {
                    if (onClear != null) onClear.run();
                } catch (Throwable ignored) {
                }
                try {
                    if (afterLocalAction != null) afterLocalAction.run();
                } catch (Throwable ignored) {
                }
            });

            row.addView(tv);
            row.addView(btnSwap);
            row.addView(btnClear);
            root.addView(row);
        } catch (Throwable ignored) {
        }
    }
}

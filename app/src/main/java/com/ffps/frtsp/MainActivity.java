package com.ffps.frtsp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText password;
    private Button serviceButton;
    private TextView status;
    private final Handler handler = new Handler();
    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.BLACK);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(8), 0, dp(8), 0);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView passwordLabel = new TextView(this);
        passwordLabel.setText("API password");
        passwordLabel.setTextSize(14);
        passwordLabel.setTextColor(Color.DKGRAY);
        passwordLabel.setPadding(0, dp(18), 0, dp(4));
        root.addView(passwordLabel);

        LinearLayout passwordRow = new LinearLayout(this);
        passwordRow.setOrientation(LinearLayout.HORIZONTAL);
        passwordRow.setGravity(Gravity.CENTER_VERTICAL);
        password = new EditText(this);
        password.setSingleLine(true);
        password.setTextSize(15);
        password.setHint("Enter password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(getSharedPreferences("settings", MODE_PRIVATE).getString("api_password", ""));
        passwordRow.addView(password, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button save = makeButton("Save", Color.rgb(55, 115, 190));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(88), dp(42));
        saveParams.leftMargin = dp(8);
        passwordRow.addView(save, saveParams);
        save.setOnClickListener(v -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("api_password", password.getText().toString()).apply();
            Toast.makeText(this, "Password saved", Toast.LENGTH_SHORT).show();
        });
        root.addView(passwordRow);

        CheckBox show = new CheckBox(this);
        show.setText("Show password");
        show.setTextColor(Color.DKGRAY);
        show.setTextSize(14);
        show.setOnCheckedChangeListener((button, checked) -> {
            int pos = password.getSelectionStart();
            password.setInputType(InputType.TYPE_CLASS_TEXT |
                (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            password.setSelection(Math.max(0, pos));
        });
        root.addView(show);

        serviceButton = makeButton("START SERVICE", Color.rgb(46, 125, 50));
        LinearLayout.LayoutParams serviceParams = new LinearLayout.LayoutParams(-2, dp(48));
        serviceParams.topMargin = dp(20);
        root.addView(serviceButton, serviceParams);
        serviceButton.setOnClickListener(v -> {
            if (CameraService.isRunning()) {
                stopService(new Intent(this, CameraService.class));
            } else {
                startService(new Intent(this, CameraService.class));
            }
            handler.postDelayed(statusUpdater, 300);
        });

        TextView hint = new TextView(this);
        hint.setText("MJPEG: http://DEVICE_IP:8554/camera/1\nSnapshot: http://DEVICE_IP:8554/camera/1.jpg\nAPI: http://DEVICE_IP:8554/api/v1/status\nBasic Auth username: api");
        hint.setTextSize(13);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, dp(14), 0, 0);
        root.addView(hint);

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        TextView version = new TextView(this);
        version.setText("v." + BuildConfig.VERSION_NAME);
        version.setTextSize(12);
        version.setTextColor(Color.GRAY);
        version.setGravity(Gravity.CENTER);
        root.addView(version, new LinearLayout.LayoutParams(-1, dp(28)));

        setContentView(root);
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusUpdater);
        handler.post(statusUpdater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(statusUpdater);
        super.onPause();
    }

    private void updateStatus() {
        boolean running = CameraService.isRunning();
        if (running) {
            status.setText("Running: http://" + getDeviceIp() + ":8554");
            serviceButton.setText("STOP SERVICE");
            serviceButton.setBackgroundDrawable(buttonBackground(Color.rgb(198, 40, 40)));
        } else {
            String error = CameraService.getLastError();
            status.setText(error == null ? "Stopped" : "Failed: " + error);
            serviceButton.setText("START SERVICE");
            serviceButton.setBackgroundDrawable(buttonBackground(Color.rgb(46, 125, 50)));
        }
    }

    private String getDeviceIp() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip != 0) return Formatter.formatIpAddress(ip);
        } catch (Exception ignored) { }
        return "DEVICE_IP";
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(true);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackgroundDrawable(buttonBackground(color));
        return button;
    }

    private GradientDrawable buttonBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(9));
        return drawable;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
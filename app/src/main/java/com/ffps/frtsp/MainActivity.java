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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private EditText password;
    private EditText port;
    private EditText cameraCount;
    private Button editCamerasButton;
    private Button saveButton;
    private Button serviceButton;
    private CheckBox showPassword;
    private TextView status;
    private TextView hint;
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
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(38)));

        setTitle("fRTSP");

        TextView portLabel = new TextView(this);
        portLabel.setText("Port");
        portLabel.setTextSize(14);
        portLabel.setTextColor(Color.DKGRAY);
        portLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(portLabel);

        port = new EditText(this);
        port.setSingleLine(true);
        port.setTextSize(15);
        port.setHint("8554");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(getSavedPort()));
        root.addView(port, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView passwordLabel = new TextView(this);
        passwordLabel.setText("API password");
        passwordLabel.setTextSize(14);
        passwordLabel.setTextColor(Color.DKGRAY);
        passwordLabel.setPadding(0, dp(14), 0, dp(4));
        root.addView(passwordLabel);

        password = new EditText(this);
        password.setSingleLine(true);
        password.setTextSize(15);
        password.setHint("Enter password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(getSharedPreferences("settings", MODE_PRIVATE).getString("api_password", ""));
        root.addView(password, new LinearLayout.LayoutParams(-1, dp(48)));

        showPassword = new CheckBox(this);
        showPassword.setText("Show password");
        showPassword.setTextColor(Color.DKGRAY);
        showPassword.setTextSize(14);
        showPassword.setOnCheckedChangeListener((button, checked) -> {
            int pos = password.getSelectionStart();
            password.setInputType(InputType.TYPE_CLASS_TEXT |
                (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            password.setSelection(Math.max(0, pos));
        });
        root.addView(showPassword);


        TextView camerasLabel = new TextView(this);
        camerasLabel.setText("Cameras");
        camerasLabel.setTextSize(14);
        camerasLabel.setTextColor(Color.DKGRAY);
        camerasLabel.setPadding(0, dp(14), 0, dp(4));
        root.addView(camerasLabel);

        LinearLayout camerasRow = new LinearLayout(this);
        camerasRow.setOrientation(LinearLayout.HORIZONTAL);
        camerasRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(camerasRow, new LinearLayout.LayoutParams(-1, dp(48)));

        cameraCount = new EditText(this);
        cameraCount.setSingleLine(true);
        cameraCount.setTextSize(15);
        cameraCount.setFocusable(false);
        cameraCount.setClickable(false);
        cameraCount.setLongClickable(false);
        cameraCount.setText(String.valueOf(getCameraCount()));
        camerasRow.addView(cameraCount, new LinearLayout.LayoutParams(0, -1, 1));

        editCamerasButton = makeButton("Edit", Color.rgb(55, 115, 190));
        LinearLayout.LayoutParams editCamerasParams = new LinearLayout.LayoutParams(dp(76), dp(44));
        editCamerasParams.leftMargin = dp(8);
        camerasRow.addView(editCamerasButton, editCamerasParams);
        editCamerasButton.setOnClickListener(v -> openCameraSettings());

        saveButton = makeButton("Save", Color.rgb(55, 115, 190));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(104), dp(46));
        saveParams.topMargin = dp(10);
        saveParams.gravity = Gravity.LEFT;
        root.addView(saveButton, saveParams);
        saveButton.setOnClickListener(v -> saveSettings());

        hint = new TextView(this);
        hint.setTextSize(13);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint);

        serviceButton = makeButton("START SERVICE", Color.rgb(46, 125, 50));
        serviceButton.setPadding(dp(16), serviceButton.getPaddingTop(), dp(16), serviceButton.getPaddingBottom());
        LinearLayout.LayoutParams serviceParams = new LinearLayout.LayoutParams(dp(220), dp(50));
        serviceParams.topMargin = dp(20);
        serviceParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(serviceButton, serviceParams);
        serviceButton.setOnClickListener(v -> {
            if (!CameraService.isRunning() && getCameraCount() == 0) return;
            if (CameraService.isRunning()) {
                stopService(new Intent(this, CameraService.class));
            } else {
                startService(new Intent(this, CameraService.class));
            }
            handler.postDelayed(statusUpdater, 300);
        });

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        TextView version = new TextView(this);
        version.setText("v." + BuildConfig.VERSION_NAME);
        version.setTextSize(12);
        version.setTextColor(Color.GRAY);
        version.setGravity(Gravity.CENTER);
        root.addView(version, new LinearLayout.LayoutParams(-1, dp(28)));

        root.setFocusableInTouchMode(true);
        setContentView(root);
        root.requestFocus();
        updateHint();
        updateStatus();
    }

    private void saveSettings() {
        String passwordValue = password.getText().toString();
        String portValue = port.getText().toString().trim();
        int portNumber;
        try {
            portNumber = Integer.parseInt(portValue);
            if (portNumber < 1 || portNumber > 65535) throw new NumberFormatException();
        } catch (Exception e) {
            port.setError("Enter a port from 1 to 65535");
            return;
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("api_password", passwordValue)
            .putInt("http_port", portNumber)
            .apply();
        updateHint();
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
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
        int currentPort = getSavedPort();
        int cameras = getCameraCount();
        cameraCount.setText(String.valueOf(cameras));
        serviceButton.setEnabled(running || cameras > 0);
        serviceButton.setAlpha(running || cameras > 0 ? 1.0f : 0.45f);
        editCamerasButton.setEnabled(true);
        editCamerasButton.setAlpha(1.0f);
        if (running) {
            status.setText("Running: http://" + getDeviceIp() + ":" + currentPort);
            serviceButton.setText("STOP SERVICE");
            serviceButton.setBackgroundDrawable(buttonBackground(Color.rgb(198, 40, 40)));
        } else {
            String error = CameraService.getLastError();
            status.setText(error == null ? "Stopped" : "Failed: " + error);
            serviceButton.setText("START SERVICE");
            serviceButton.setBackgroundDrawable(buttonBackground(Color.rgb(46, 125, 50)));
        }
        password.setEnabled(!running);
        port.setEnabled(!running);
        showPassword.setEnabled(!running);
        saveButton.setEnabled(!running);
        saveButton.setAlpha(running ? 0.45f : 1.0f);
    }

    private void updateHint() {
        int p = getSavedPort();
        String base = "http://" + getDeviceIp() + ":" + p;
        hint.setText("MJPEG: " + base + "/cam/{name}\n" +
            "Snapshot: " + base + "/cam/{name}.jpg\n" +
            "API: " + base + "/api/v1/status\n" +
            "Basic Auth username: api");
    }

    private int getSavedPort() {
        return getSharedPreferences("settings", MODE_PRIVATE).getInt("http_port", 8554);
    }

    private int getCameraCount() {
        String raw = getSharedPreferences("settings", MODE_PRIVATE).getString("cameras_json", "[]");
        try {
            return new org.json.JSONArray(raw).length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress() &&
                        address instanceof Inet4Address && address.getHostAddress() != null) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip != 0) return Formatter.formatIpAddress(ip);
        } catch (Exception ignored) { }
        return "DEVICE_IP";
    }

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, "Cameras")
            .setIcon(android.R.drawable.ic_menu_camera)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 2, 1, "About")
            .setIcon(android.R.drawable.ic_menu_info_details)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            openCameraSettings();
            return true;
        }
        if (item.getItemId() == 2) {
            AboutDialog.show(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openCameraSettings() {
        startActivity(new Intent(this, CameraSettingsActivity.class));
        overridePendingTransition(0, 0);
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundDrawable(buttonBackground(color));
        return button;
    }

    private GradientDrawable buttonBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(28));
        return drawable;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

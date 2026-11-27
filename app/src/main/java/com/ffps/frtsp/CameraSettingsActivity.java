package com.ffps.frtsp;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class CameraSettingsActivity extends Activity {
    private static final String PREFS = "settings";
    private static final String KEY_CAMERAS = "cameras_json";
    private static final String NAME_PATTERN = "^[a-z][a-z0-9_]{0,31}$";

    private final List<CameraEntry> cameras = new ArrayList<>();
    private LinearLayout cameraList;
    private int textColor;
    private int mutedColor;

    private static class CameraEntry {
        String name;
        String url;
        boolean enabled;

        CameraEntry(String name, String url, boolean enabled) {
            this.name = name;
            this.url = url;
            this.enabled = enabled;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        textColor = 0xff202124;
        mutedColor = 0xff666666;
        loadCameras();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xffffffff);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Cameras");
        title.setTextSize(22);
        title.setTextColor(textColor);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("Each camera has a unique identifier used in its HTTP address.");
        description.setTextSize(13);
        description.setTextColor(mutedColor);
        root.addView(description);

        cameraList = new LinearLayout(this);
        cameraList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, -2);
        listParams.topMargin = dp(14);
        root.addView(cameraList, listParams);

        Button addButton = makeButton("ADD CAMERA", 0xff477eae);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(48));
        addParams.topMargin = dp(12);
        root.addView(addButton, addParams);
        addButton.setOnClickListener(v -> showCameraDialog(-1));

        Button saveButton = makeButton("SAVE", 0xff2e7d32);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(48));
        saveParams.topMargin = dp(10);
        root.addView(saveButton, saveParams);
        saveButton.setOnClickListener(v -> saveCameras());

        Button backButton = makeButton("BACK", 0xff777777);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-1, dp(44));
        backParams.topMargin = dp(6);
        root.addView(backButton, backParams);
        backButton.setOnClickListener(v -> finish());

        setContentView(scroll);
        renderCameras();
    }

    private void loadCameras() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CAMERAS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "");
                String url = item.optString("url", "");
                boolean enabled = item.optBoolean("enabled", true);
                if (name.matches(NAME_PATTERN) && isValidRtspUrl(url) && !containsName(name)) {
                    cameras.add(new CameraEntry(name, url, enabled));
                }
            }
        } catch (Exception ignored) { }
    }

    private void saveCameras() {
        try {
            JSONArray array = new JSONArray();
            for (CameraEntry camera : cameras) {
                JSONObject item = new JSONObject();
                item.put("name", camera.name);
                item.put("url", camera.url);
                item.put("enabled", camera.enabled);
                array.put(item);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CAMERAS, array.toString()).apply();
            Toast.makeText(this, "Camera settings saved", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save camera settings", Toast.LENGTH_LONG).show();
        }
    }

    private void renderCameras() {
        cameraList.removeAllViews();
        if (cameras.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No cameras configured");
            empty.setTextSize(14);
            empty.setTextColor(mutedColor);
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            cameraList.addView(empty);
            return;
        }

        for (int i = 0; i < cameras.size(); i++) {
            final int index = i;
            CameraEntry camera = cameras.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(9), dp(12), dp(9));
            card.setBackgroundColor(0xfff2f4f6);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(8);
            cameraList.addView(card, cardParams);

            LinearLayout heading = new LinearLayout(this);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(heading, new LinearLayout.LayoutParams(-1, -2));

            TextView name = new TextView(this);
            name.setText(camera.name);
            name.setTextSize(17);
            name.setTextColor(textColor);
            name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

            CheckBox enabled = new CheckBox(this);
            enabled.setText("Enabled");
            enabled.setTextSize(13);
            enabled.setTextColor(mutedColor);
            enabled.setChecked(camera.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> camera.enabled = checked);
            heading.addView(enabled);

            TextView url = new TextView(this);
            url.setText(camera.url);
            url.setTextSize(12);
            url.setTextColor(mutedColor);
            url.setPadding(0, dp(3), 0, dp(5));
            url.setSingleLine(false);
            card.addView(url);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(actions);

            Button edit = smallButton("EDIT");
            actions.addView(edit, new LinearLayout.LayoutParams(0, dp(38), 1));
            edit.setOnClickListener(v -> showCameraDialog(index));

            Button delete = smallButton("DELETE");
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(38), 1);
            deleteParams.leftMargin = dp(8);
            actions.addView(delete, deleteParams);
            delete.setOnClickListener(v -> confirmDelete(index));
        }
    }

    private void showCameraDialog(int editIndex) {
        final boolean editing = editIndex >= 0;
        final CameraEntry original = editing ? cameras.get(editIndex) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        TextView nameLabel = fieldLabel("Camera Name");
        form.addView(nameLabel);
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setTextSize(15);
        nameInput.setHint("parking");
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (editing) nameInput.setText(original.name);
        form.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView urlLabel = fieldLabel("RTSP URL");
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.topMargin = dp(12);
        form.addView(urlLabel, labelParams);
        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(14);
        urlInput.setHint("rtsp://user:password@192.168.2.8:554/1/2");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (editing) urlInput.setText(original.url);
        form.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(48)));

        CheckBox enabledInput = new CheckBox(this);
        enabledInput.setText("Enabled");
        enabledInput.setTextColor(textColor);
        enabledInput.setChecked(!editing || original.enabled);
        form.addView(enabledInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(editing ? "Edit camera" : "Add camera")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String url = urlInput.getText().toString().trim();
            if (!name.matches(NAME_PATTERN)) {
                nameInput.setError("Use a-z, 0-9 and _. Start with a letter; max 32 characters.");
                return;
            }
            if (containsName(name) && (!editing || !original.name.equals(name))) {
                nameInput.setError("This camera identifier is already used");
                return;
            }
            if (!isValidRtspUrl(url)) {
                urlInput.setError("Enter a valid rtsp:// URL");
                return;
            }
            if (editing) {
                original.name = name;
                original.url = url;
                original.enabled = enabledInput.isChecked();
            } else {
                cameras.add(new CameraEntry(name, url, enabledInput.isChecked()));
            }
            renderCameras();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void confirmDelete(int index) {
        String name = cameras.get(index).name;
        new AlertDialog.Builder(this)
            .setTitle("Delete camera?")
            .setMessage("Remove camera '" + name + "' from the configuration?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                cameras.remove(index);
                renderCameras();
            })
            .show();
    }

    private boolean containsName(String name) {
        for (CameraEntry camera : cameras) {
            if (camera.name.equals(name)) return true;
        }
        return false;
    }

    private boolean isValidRtspUrl(String value) {
        if (value == null || value.length() == 0 || value.indexOf(' ') >= 0) return false;
        try {
            URI uri = new URI(value);
            return "rtsp".equalsIgnoreCase(uri.getScheme()) &&
                uri.getHost() != null && uri.getHost().length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private TextView fieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(mutedColor);
        return label;
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(0xffffffff);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        button.setAllCaps(true);
        button.setBackgroundDrawable(buttonBackground(color));
        return button;
    }

    private Button smallButton(String text) {
        Button button = makeButton(text, 0xff647789);
        button.setTextSize(11);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private android.graphics.drawable.GradientDrawable buttonBackground(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

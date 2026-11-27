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
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
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
    private LinearLayout cameraRows;
    private TextView serviceNotice;
    private Button addButton;
    private final android.os.Handler handler = new android.os.Handler();
    private final Runnable stateUpdater = new Runnable() {
        @Override public void run() {
            updateEditableState();
            handler.postDelayed(this, 1000);
        }
    };

    private static class CameraEntry {
        String name;
        String url;
        boolean enabled;
        int fps;
        int quality;

        CameraEntry(String name, String url, boolean enabled, int fps, int quality) {
            this.name = name;
            this.url = url;
            this.enabled = enabled;
            this.fps = fps;
            this.quality = quality;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        loadCameras();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.setBackgroundColor(0xffffffff);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(46)));
        Button settingsTab = tabButton("SETTINGS", 0xff777f88);
        tabs.addView(settingsTab, new LinearLayout.LayoutParams(0, -1, 1));
        Button camerasTab = tabButton("CAMERAS", 0xff3773be);
        LinearLayout.LayoutParams tab2 = new LinearLayout.LayoutParams(0, -1, 1);
        tab2.leftMargin = dp(6);
        tabs.addView(camerasTab, tab2);
        camerasTab.setEnabled(false);
        settingsTab.setOnClickListener(v -> finish());

        serviceNotice = new TextView(this);
        serviceNotice.setTextSize(13);
        serviceNotice.setTextColor(0xffa05a00);
        serviceNotice.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(serviceNotice);

        addButton = tabButton("ADD CAMERA", 0xff477eae);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(44));
        addParams.topMargin = dp(6);
        root.addView(addButton, addParams);
        addButton.setOnClickListener(v -> showCameraDialog(-1));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(false);
        horizontal.setHorizontalScrollBarEnabled(true);
        LinearLayout.LayoutParams tableScrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        tableScrollParams.topMargin = dp(10);
        root.addView(horizontal, tableScrollParams);

        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        horizontal.addView(table);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(0xffe5e9ed);
        table.addView(header);
        addCell(header, "Enabled", 76, true);
        addCell(header, "Name", 88, true);
        addCell(header, "URL", 250, true);
        addCell(header, "FPS", 48, true);
        addCell(header, "Quality", 66, true);
        addCell(header, "Action", 92, true);

        ScrollView vertical = new ScrollView(this);
        table.addView(vertical, new LinearLayout.LayoutParams(-1, -1));
        cameraRows = new LinearLayout(this);
        cameraRows.setOrientation(LinearLayout.VERTICAL);
        vertical.addView(cameraRows);

        setContentView(root);
        renderRows();
        updateEditableState();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(stateUpdater);
        handler.post(stateUpdater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(stateUpdater);
        super.onPause();
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
                int fps = item.optInt("fps", 1);
                int quality = item.optInt("quality", 75);
                if (name.matches(NAME_PATTERN) && isValidRtspUrl(url) && !containsName(name) &&
                    fps >= 1 && fps <= 60 && quality >= 1 && quality <= 100) {
                    cameras.add(new CameraEntry(name, url, enabled, fps, quality));
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
                item.put("fps", camera.fps);
                item.put("quality", camera.quality);
                array.put(item);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CAMERAS, array.toString()).apply();
            return;
        } catch (Exception e) {
            Toast.makeText(this, "Could not save camera settings", Toast.LENGTH_LONG).show();
        }
    }

    private void renderRows() {
        cameraRows.removeAllViews();
        if (cameras.isEmpty()) {
            LinearLayout row = new LinearLayout(this);
            cameraRows.addView(row);
            TextView empty = new TextView(this);
            empty.setText("No cameras configured");
            empty.setTextColor(0xff666666);
            empty.setPadding(dp(8), dp(14), dp(8), dp(14));
            row.addView(empty);
            return;
        }

        for (int i = 0; i < cameras.size(); i++) {
            final int index = i;
            CameraEntry camera = cameras.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(i % 2 == 0 ? 0xfffafbfc : 0xfff0f2f4);
            cameraRows.addView(row, new LinearLayout.LayoutParams(-2, dp(54)));

            CheckBox enabled = new CheckBox(this);
            enabled.setGravity(Gravity.CENTER);
            enabled.setChecked(camera.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                if (CameraService.isRunning()) {
                    button.setChecked(!checked);
                    return;
                }
                camera.enabled = checked;
                saveCameras();
            });
            addViewCell(row, enabled, 76);

            addTextCell(row, camera.name, 88, true);
            addTextCell(row, camera.url, 250, false);
            addTextCell(row, String.valueOf(camera.fps), 48, true);
            addTextCell(row, String.valueOf(camera.quality), 66, true);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            row.addView(actions, new LinearLayout.LayoutParams(dp(92), -1));

            ImageButton edit = iconButton(android.R.drawable.ic_menu_edit, "Edit");
            actions.addView(edit, new LinearLayout.LayoutParams(dp(42), dp(42)));
            edit.setOnClickListener(v -> {
                if (!CameraService.isRunning()) showCameraDialog(index);
            });

            ImageButton delete = iconButton(android.R.drawable.ic_menu_delete, "Delete");
            actions.addView(delete, new LinearLayout.LayoutParams(dp(42), dp(42)));
            delete.setOnClickListener(v -> {
                if (!CameraService.isRunning()) confirmDelete(index);
            });
        }
    }

    private void showCameraDialog(int editIndex) {
        if (CameraService.isRunning()) return;
        final boolean editing = editIndex >= 0;
        final CameraEntry original = editing ? cameras.get(editIndex) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(4), pad, 0);

        form.addView(fieldLabel("Camera Name"));
        EditText nameInput = input("name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (editing) nameInput.setText(original.name);
        form.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = dp(8);
        form.addView(fieldLabel("RTSP URL"), gap);
        EditText urlInput = input("rtsp://user:password@192.168.2.8:554/1/2",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (editing) urlInput.setText(original.url);
        form.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout numbers = new LinearLayout(this);
        numbers.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams numbersParams = new LinearLayout.LayoutParams(-1, -2);
        numbersParams.topMargin = dp(8);
        form.addView(numbers, numbersParams);

        LinearLayout fpsBox = new LinearLayout(this);
        fpsBox.setOrientation(LinearLayout.VERTICAL);
        numbers.addView(fpsBox, new LinearLayout.LayoutParams(0, -2, 1));
        fpsBox.addView(fieldLabel("Output FPS"));
        EditText fpsInput = input("1", InputType.TYPE_CLASS_NUMBER);
        fpsInput.setText(editing ? String.valueOf(original.fps) : "1");
        fpsBox.addView(fpsInput, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout qualityBox = new LinearLayout(this);
        qualityBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(0, -2, 1);
        qualityParams.leftMargin = dp(12);
        numbers.addView(qualityBox, qualityParams);
        qualityBox.addView(fieldLabel("JPEG Quality"));
        EditText qualityInput = input("75", InputType.TYPE_CLASS_NUMBER);
        qualityInput.setText(editing ? String.valueOf(original.quality) : "75");
        qualityBox.addView(qualityInput, new LinearLayout.LayoutParams(-1, dp(46)));

        CheckBox enabledInput = new CheckBox(this);
        enabledInput.setText("Enabled");
        enabledInput.setTextColor(0xff333333);
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
            String url = normalizeRtspUrl(urlInput.getText().toString().trim());
            int fps;
            int quality;
            try {
                fps = Integer.parseInt(fpsInput.getText().toString().trim());
                if (fps < 1 || fps > 60) throw new NumberFormatException();
            } catch (Exception e) {
                fpsInput.setError("Enter FPS from 1 to 60");
                return;
            }
            try {
                quality = Integer.parseInt(qualityInput.getText().toString().trim());
                if (quality < 1 || quality > 100) throw new NumberFormatException();
            } catch (Exception e) {
                qualityInput.setError("Enter quality from 1 to 100");
                return;
            }
            if (!name.matches(NAME_PATTERN)) {
                nameInput.setError("Use a-z, 0-9 and _. Start with a letter; max 32 characters.");
                return;
            }
            if (containsName(name) && (!editing || !original.name.equals(name))) {
                nameInput.setError("This camera identifier is already used");
                return;
            }
            if (!isValidRtspUrl(url)) {
                urlInput.setError("Enter a valid RTSP URL");
                return;
            }
            if (editing) {
                original.name = name;
                original.url = url;
                original.enabled = enabledInput.isChecked();
                original.fps = fps;
                original.quality = quality;
            } else {
                cameras.add(new CameraEntry(name, url, enabledInput.isChecked(), fps, quality));
            }
            saveCameras();
            renderRows();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void confirmDelete(int index) {
        if (CameraService.isRunning()) return;
        String name = cameras.get(index).name;
        new AlertDialog.Builder(this)
            .setTitle("Delete camera?")
            .setMessage("Remove camera '" + name + "' from the configuration?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                if (CameraService.isRunning()) return;
                cameras.remove(index);
                saveCameras();
                renderRows();
            })
            .show();
    }

    private void updateEditableState() {
        boolean editable = !CameraService.isRunning();
        addButton.setEnabled(editable);
        addButton.setAlpha(editable ? 1f : 0.45f);
        serviceNotice.setText(editable ? "" : "Service is running — camera settings are read-only.");
        for (int i = 0; i < cameraRows.getChildCount(); i++) {
            View row = cameraRows.getChildAt(i);
            setRowEnabled(row, editable);
        }
    }

    private void setRowEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setRowEnabled(group.getChildAt(i), enabled);
        }
        view.setAlpha(enabled ? 1f : 0.5f);
    }

    private String normalizeRtspUrl(String value) {
        if (value.length() == 0) return value;
        if (!value.regionMatches(true, 0, "rtsp://", 0, 7)) return "rtsp://" + value;
        return value;
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

    private boolean containsName(String name) {
        for (CameraEntry camera : cameras) if (camera.name.equals(name)) return true;
        return false;
    }

    private EditText input(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setHint(hint);
        edit.setInputType(type);
        return edit;
    }

    private TextView fieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(0xff666666);
        return label;
    }

    private Button tabButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(0xffffffff);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        button.setAllCaps(true);
        button.setBackgroundDrawable(buttonBackground(color));
        return button;
    }

    private ImageButton iconButton(int resource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setContentDescription(description);
        button.setBackgroundDrawable(buttonBackground(0xffe2e7eb));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        return button;
    }

    private void addCell(LinearLayout row, String text, int widthDp, boolean centered) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextSize(12);
        cell.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        cell.setTextColor(0xff333333);
        cell.setGravity(centered ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(5), dp(6), dp(5), dp(6));
        row.addView(cell, new LinearLayout.LayoutParams(dp(widthDp), dp(42)));
    }

    private void addTextCell(LinearLayout row, String text, int widthDp, boolean centered) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextSize(12);
        cell.setTextColor(0xff333333);
        cell.setGravity(centered ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(5), dp(4), dp(5), dp(4));
        if (!centered) cell.setSingleLine(true);
        row.addView(cell, new LinearLayout.LayoutParams(dp(widthDp), -1));
    }

    private void addViewCell(LinearLayout row, View view, int widthDp) {
        LinearLayout holder = new LinearLayout(this);
        holder.setGravity(Gravity.CENTER);
        holder.addView(view, new LinearLayout.LayoutParams(dp(42), dp(46)));
        row.addView(holder, new LinearLayout.LayoutParams(dp(widthDp), -1));
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

    private android.graphics.drawable.GradientDrawable buttonBackground(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(22));
        return drawable;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

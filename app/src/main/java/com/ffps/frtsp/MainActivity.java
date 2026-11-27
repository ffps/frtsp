package com.ffps.frtsp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText password;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("fRTSP");
        title.setTextSize(26);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("HTTP server: порт 8554\\nMJPEG: /camera/1\\nSnapshot: /camera/1.jpg\\nAPI: /api/v1/status");
        info.setPadding(0, dp(12), 0, dp(18));
        root.addView(info);

        TextView label = new TextView(this);
        label.setText("Пароль API (Basic Auth для запросов из локальной сети)");
        root.addView(label);

        password = new EditText(this);
        password.setSingleLine(true);
        password.setHint("Пустой пароль запрещает внешний API");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(getSharedPreferences("settings", MODE_PRIVATE).getString("api_password", ""));
        root.addView(password);

        CheckBox show = new CheckBox(this);
        show.setText("Показать пароль");
        show.setOnCheckedChangeListener((button, checked) ->
            password.setInputType(InputType.TYPE_CLASS_TEXT |
                (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD)));
        root.addView(show);

        Button save = new Button(this);
        save.setText("Сохранить пароль");
        save.setOnClickListener(v -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("api_password", password.getText().toString()).apply();
            Toast.makeText(this, "Пароль API сохранён", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);

        Button start = new Button(this);
        start.setText("Запустить сервер");
        start.setOnClickListener(v -> {
            startService(new Intent(this, CameraService.class));
            Toast.makeText(this, "Служба запускается", Toast.LENGTH_SHORT).show();
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Остановить сервер");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CameraService.class));
            Toast.makeText(this, "Служба остановлена", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);
        setContentView(root);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

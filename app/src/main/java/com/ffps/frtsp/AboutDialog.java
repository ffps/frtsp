package com.ffps.frtsp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class AboutDialog {
    private AboutDialog() { }

    public static void show(Activity activity) {
        int pad = dp(activity, 20);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(pad, dp(activity, 12), pad, dp(activity, 4));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(activity.getApplicationInfo().icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(activity, 72), dp(activity, 72));
        iconParams.bottomMargin = dp(activity, 10);
        content.addView(icon, iconParams);

        TextView name = new TextView(activity);
        name.setText("fRTSP");
        name.setTextSize(20);
        name.setTextColor(0xff222222);
        name.setGravity(Gravity.CENTER);
        content.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView version = new TextView(activity);
        version.setText("Version " + BuildConfig.VERSION_NAME);
        version.setTextSize(14);
        version.setTextColor(0xff666666);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-1, -2);
        versionParams.topMargin = dp(activity, 4);
        content.addView(version, versionParams);

        TextView project = new TextView(activity);
        project.setText("github.com/ffps/frtsp");
        project.setTextSize(14);
        project.setTextColor(0xff2875bd);
        project.setGravity(Gravity.CENTER);
        project.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        project.setOnClickListener(v -> activity.startActivity(
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ffps/frtsp"))));
        content.addView(project, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(activity)
            .setView(content)
            .setPositiveButton("OK", null)
            .show();
    }

    private static int dp(Activity activity, int value) {
        return (int)(value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}

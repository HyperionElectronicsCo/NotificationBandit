package com.hyperion.notificationbandit;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 5001;
    private static final String POST_NOTIFICATIONS_PERMISSION =
            "android.permission.POST_NOTIFICATIONS";
    private TextView status_text;
    private final Handler status_handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status_text = (TextView) findViewById(R.id.status_text);
        Button start_button = (Button) findViewById(R.id.start_button);
        Button reset_button = (Button) findViewById(R.id.reset_button);

        start_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestPermissionAndStart();
            }
        });

        reset_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(POST_NOTIFICATIONS_PERMISSION)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionAndStart();
                    return;
                }

                Intent reset_intent = new Intent(MainActivity.this, SlotMachineService.class);
                reset_intent.setAction(SlotMachineService.ACTION_RESET);
                if (startSlotService(reset_intent)) {
                    Toast.makeText(MainActivity.this, "Credits reset to 100", Toast.LENGTH_SHORT).show();
                }
            }
        });

        requestPermissionAndStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void requestPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(POST_NOTIFICATIONS_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{POST_NOTIFICATIONS_PERMISSION},
                    NOTIFICATION_PERMISSION_REQUEST
            );
            return;
        }

        Intent start_intent = new Intent(this, SlotMachineService.class);
        start_intent.setAction(SlotMachineService.ACTION_START);
        if (startSlotService(start_intent)) {
            status_text.setText("Status: starting notification...");
            status_handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateStatus();
                }
            }, 500L);
        }
    }

    private boolean startSlotService(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            return true;
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.length() == 0) {
                message = exception.getClass().getSimpleName();
            }
            status_text.setText("Status: service could not start - " + message);
            Toast.makeText(this, "Could not start notification: " + message, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void updateStatus() {
        if (status_text == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(POST_NOTIFICATIONS_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            status_text.setText("Status: notification permission required");
            return;
        }

        String service_error = SlotMachineService.getLastServiceError(this);
        if (service_error != null && service_error.length() > 0) {
            status_text.setText("Status: " + service_error);
        } else {
            status_text.setText("Status: notification active or ready to restore");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestPermissionAndStart();
            } else {
                status_text.setText("Status: permission denied - enable notifications in Android settings");
                Toast.makeText(this, "Notification permission is needed for the slot machine", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        status_handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

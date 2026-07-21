package com.hyperion.notificationbandit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Random;

public class SlotMachineService extends Service {

    public static final String ACTION_START = "com.hyperion.notificationbandit.action.START";
    public static final String ACTION_SPIN = "com.hyperion.notificationbandit.action.SPIN";
    public static final String ACTION_RESET = "com.hyperion.notificationbandit.action.RESET";
    public static final String ACTION_BET_ONE = "com.hyperion.notificationbandit.action.BET_ONE";
    public static final String ACTION_BET_TWO = "com.hyperion.notificationbandit.action.BET_TWO";
    public static final String ACTION_BET_FIVE = "com.hyperion.notificationbandit.action.BET_FIVE";
    public static final String ACTION_BET_TEN = "com.hyperion.notificationbandit.action.BET_TEN";
    public static final String ACTION_BET_NEXT = "com.hyperion.notificationbandit.action.BET_NEXT";

    private static final String CHANNEL_ID = "notification_bandit_channel";
    private static final int NOTIFICATION_ID = 777;
    private static final String PREFS = "slot_machine_prefs";
    private static final String KEY_CREDITS = "credits";
    private static final String KEY_BET = "bet";
    private static final String KEY_REEL_ONE = "reel_one";
    private static final String KEY_REEL_TWO = "reel_two";
    private static final String KEY_REEL_THREE = "reel_three";
    private static final String KEY_RESULT = "result";
    private static final String KEY_SERVICE_ERROR = "service_error";

    private static final int[] BET_VALUES = new int[]{1, 2, 5, 10};

    private static final int[] SYMBOLS = new int[]{
            R.drawable.symbol_cherry,
            R.drawable.symbol_lemon,
            R.drawable.symbol_bell,
            R.drawable.symbol_bar,
            R.drawable.symbol_seven
    };

    private static final int[] SPIN_FRAMES = new int[]{
            R.drawable.reel_spin_00,
            R.drawable.reel_spin_01,
            R.drawable.reel_spin_02,
            R.drawable.reel_spin_03,
            R.drawable.reel_spin_04,
            R.drawable.reel_spin_05,
            R.drawable.reel_spin_06,
            R.drawable.reel_spin_07,
            R.drawable.reel_spin_08,
            R.drawable.reel_spin_09
    };

    private final Random random = new Random();
    private final Handler animation_handler = new Handler(Looper.getMainLooper());

    private boolean spinning;
    private boolean lever_down;
    private int spin_token;
    private int spin_tick;
    private int spin_frame;
    private int final_reel_one;
    private int final_reel_two;
    private int final_reel_three;
    private int active_bet;
    private int animation_token = -1;
    private int final_redraw_token = -1;

    /*
     * Android SystemUI can throttle rapid notification updates. Reposting the
     * completed result after a short pause prevents the final spinning frame
     * from remaining over the third reel.
     */
    private final Runnable final_redraw_runnable = new Runnable() {
        @Override
        public void run() {
            if (spinning || final_redraw_token != spin_token) {
                return;
            }

            updateNotificationSafely();
        }
    };

    private final Runnable animation_runnable = new Runnable() {
        @Override
        public void run() {
            if (!spinning || animation_token != spin_token) {
                return;
            }

            spin_tick++;
            spin_frame = (spin_frame + 1) % SPIN_FRAMES.length;
            lever_down = spin_tick < 3;

            if (spin_tick >= 17) {
                finishSpin();
                return;
            }

            updateNotificationSafely();
            animation_handler.postDelayed(this, 125L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        /*
         * Start with a plain Android notification first. This keeps the service
         * alive even if an OEM rejects a custom RemoteViews layout.
         */
        try {
            startForeground(NOTIFICATION_ID, buildBaseNotification());
        } catch (RuntimeException exception) {
            saveServiceError("Unable to start notification service: " + safeMessage(exception));
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SPIN.equals(action)) {
            beginSpin();
        } else if (ACTION_RESET.equals(action)) {
            resetGame();
        } else if (ACTION_BET_ONE.equals(action)) {
            setBet(1);
        } else if (ACTION_BET_TWO.equals(action)) {
            setBet(2);
        } else if (ACTION_BET_FIVE.equals(action)) {
            setBet(5);
        } else if (ACTION_BET_TEN.equals(action)) {
            setBet(10);
        } else if (ACTION_BET_NEXT.equals(action)) {
            selectNextBet();
        }

        updateNotificationSafely();
        return START_STICKY;
    }

    private void beginSpin() {
        if (spinning) {
            return;
        }

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int credits = preferences.getInt(KEY_CREDITS, 100);
        int bet = sanitiseBet(preferences.getInt(KEY_BET, 1));

        if (credits < bet) {
            preferences.edit()
                    .putString(KEY_RESULT, "BET DECLINED - NEED " + bet + " - BALANCE " + credits)
                    .apply();
            return;
        }

        credits -= bet;
        active_bet = bet;
        final_reel_one = random.nextInt(SYMBOLS.length);
        final_reel_two = random.nextInt(SYMBOLS.length);
        final_reel_three = random.nextInt(SYMBOLS.length);

        preferences.edit()
                .putInt(KEY_CREDITS, credits)
                .putString(KEY_RESULT, "SPINNING - BET " + bet)
                .putString(KEY_SERVICE_ERROR, "")
                .apply();

        animation_handler.removeCallbacks(animation_runnable);
        animation_handler.removeCallbacks(final_redraw_runnable);

        spin_token++;
        animation_token = spin_token;
        final_redraw_token = -1;

        spinning = true;
        lever_down = true;
        spin_tick = 0;
        spin_frame = random.nextInt(SPIN_FRAMES.length);

        updateNotificationSafely();
        animation_handler.postDelayed(animation_runnable, 125L);
    }

    private void finishSpin() {
        if (!spinning || animation_token != spin_token) {
            return;
        }

        final int completed_token = spin_token;

        /*
         * Stop the animation before saving or drawing anything. This prevents
         * an already queued animation callback from replacing the final reel.
         */
        spinning = false;
        lever_down = false;
        spin_tick = 17;

        animation_handler.removeCallbacks(animation_runnable);
        animation_handler.removeCallbacks(final_redraw_runnable);

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int credits = preferences.getInt(KEY_CREDITS, 100);

        int payout_multiplier = calculatePayoutMultiplier(
                final_reel_one,
                final_reel_two,
                final_reel_three
        );

        int payout = payout_multiplier * active_bet;
        credits += payout;

        String result;

        if (payout_multiplier >= 50) {
            result = "JACKPOT! WON +" + payout + " - BALANCE " + credits;
        } else if (payout > 0) {
            result = "WIN! +" + payout + " - BALANCE " + credits;
        } else {
            result = "LOST -" + active_bet + " - BALANCE " + credits;
        }

        /*
         * commit() is intentional here. The final notification must not be
         * constructed until all three completed reel values are available.
         */
        preferences.edit()
                .putInt(KEY_CREDITS, credits)
                .putInt(KEY_REEL_ONE, final_reel_one)
                .putInt(KEY_REEL_TWO, final_reel_two)
                .putInt(KEY_REEL_THREE, final_reel_three)
                .putString(KEY_RESULT, result)
                .commit();

        final_redraw_token = completed_token;

        /*
         * Draw immediately, then repeat after SystemUI's notification update
         * throttle has cleared. Both delayed redraws are cancelled by a new
         * spin and cannot overwrite the next result.
         */
        updateNotificationSafely();
        animation_handler.postDelayed(final_redraw_runnable, 250L);
        animation_handler.postDelayed(final_redraw_runnable, 700L);
    }

    private int calculatePayoutMultiplier(int one, int two, int three) {
        if (one == 4 && two == 4 && three == 4) {
            return 50;
        }
        if (one == 3 && two == 3 && three == 3) {
            return 25;
        }
        if (one == 2 && two == 2 && three == 2) {
            return 15;
        }
        if (one == 1 && two == 1 && three == 1) {
            return 10;
        }
        if (one == 0 && two == 0 && three == 0) {
            return 5;
        }
        if (one == two || one == three || two == three) {
            return 2;
        }
        return 0;
    }

    private void setBet(int bet) {
        if (spinning) {
            return;
        }

        int safe_bet = sanitiseBet(bet);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_BET, safe_bet)
                .putString(KEY_RESULT, "BET " + safe_bet + " SELECTED - PULL LEVER")
                .apply();
    }

    private void selectNextBet() {
        if (spinning) {
            return;
        }

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int current = sanitiseBet(preferences.getInt(KEY_BET, 1));
        int next = BET_VALUES[0];

        for (int i = 0; i < BET_VALUES.length; i++) {
            if (BET_VALUES[i] == current) {
                next = BET_VALUES[(i + 1) % BET_VALUES.length];
                break;
            }
        }

        setBet(next);
    }

    private int sanitiseBet(int bet) {
        for (int i = 0; i < BET_VALUES.length; i++) {
            if (BET_VALUES[i] == bet) {
                return bet;
            }
        }
        return 1;
    }

    private void resetGame() {
        spin_token++;
        animation_handler.removeCallbacksAndMessages(null);
        spinning = false;
        lever_down = false;
        spin_tick = 0;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_CREDITS, 100)
                .putInt(KEY_BET, 1)
                .putInt(KEY_REEL_ONE, 0)
                .putInt(KEY_REEL_TWO, 1)
                .putInt(KEY_REEL_THREE, 4)
                .putString(KEY_RESULT, "READY - CREDITS RESET TO 100")
                .putString(KEY_SERVICE_ERROR, "")
                .apply();
    }

    private Notification buildBaseNotification() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int credits = preferences.getInt(KEY_CREDITS, 100);
        int bet = sanitiseBet(preferences.getInt(KEY_BET, 1));
        String result = preferences.getString(KEY_RESULT, "READY - PULL THE LEVER");

        PendingIntent spin_pending_intent = buildServicePendingIntent(ACTION_SPIN, 1001);
        PendingIntent next_bet_pending_intent = buildServicePendingIntent(ACTION_BET_NEXT, 1002);
        PendingIntent open_pending_intent = buildOpenPendingIntent();

        Notification.Builder builder = newNotificationBuilder();
        builder.setSmallIcon(R.drawable.ic_stat_slot)
                .setContentTitle("Notification Bandit")
                .setContentText(spinning ? "SPINNING - BET " + active_bet : result)
                .setContentIntent(open_pending_intent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(R.drawable.ic_stat_slot, "PULL LEVER", spin_pending_intent)
                .addAction(R.drawable.ic_stat_slot, "CHANGE BET", next_bet_pending_intent);

        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        return notification;
    }

    private Notification buildCustomNotification() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int credits = preferences.getInt(KEY_CREDITS, 100);
        int bet = sanitiseBet(preferences.getInt(KEY_BET, 1));
        int stored_reel_one = preferences.getInt(KEY_REEL_ONE, 0);
        int stored_reel_two = preferences.getInt(KEY_REEL_TWO, 1);
        int stored_reel_three = preferences.getInt(KEY_REEL_THREE, 4);
        String result = preferences.getString(KEY_RESULT, "Pull the lever to spin");

        RemoteViews compact_view = new RemoteViews(getPackageName(), R.layout.notification_slot_compact);
        RemoteViews big_view = new RemoteViews(getPackageName(), R.layout.notification_slot_big);

        PendingIntent spin_pending_intent = buildServicePendingIntent(ACTION_SPIN, 1001);
        PendingIntent next_bet_pending_intent = buildServicePendingIntent(ACTION_BET_NEXT, 1002);

        compact_view.setOnClickPendingIntent(R.id.compact_lever_button, spin_pending_intent);
        compact_view.setOnClickPendingIntent(R.id.compact_bet_button, next_bet_pending_intent);
        compact_view.setImageViewResource(
                R.id.compact_lever_button,
                lever_down ? R.drawable.lever_down : R.drawable.lever_up
        );
        compact_view.setTextViewText(R.id.compact_bet_button, "BET " + bet);
        compact_view.setTextViewText(
                R.id.compact_status,
                spinning ? "SPINNING - BET " + active_bet + " - BAL " + credits : result
        );

        big_view.setOnClickPendingIntent(R.id.lever_button, spin_pending_intent);
        big_view.setOnClickPendingIntent(R.id.bet_one_button, buildServicePendingIntent(ACTION_BET_ONE, 1101));
        big_view.setOnClickPendingIntent(R.id.bet_two_button, buildServicePendingIntent(ACTION_BET_TWO, 1102));
        big_view.setOnClickPendingIntent(R.id.bet_five_button, buildServicePendingIntent(ACTION_BET_FIVE, 1105));
        big_view.setOnClickPendingIntent(R.id.bet_ten_button, buildServicePendingIntent(ACTION_BET_TEN, 1110));

        big_view.setImageViewResource(
                R.id.lever_button,
                lever_down ? R.drawable.lever_down : R.drawable.lever_up
        );

        big_view.setImageViewResource(R.id.final_reel_one, SYMBOLS[safeIndex(stored_reel_one)]);
        big_view.setImageViewResource(R.id.final_reel_two, SYMBOLS[safeIndex(stored_reel_two)]);
        big_view.setImageViewResource(R.id.final_reel_three, SYMBOLS[safeIndex(stored_reel_three)]);

        big_view.setImageViewResource(R.id.spin_reel_one, SPIN_FRAMES[spin_frame]);
        big_view.setImageViewResource(R.id.spin_reel_two, SPIN_FRAMES[(spin_frame + 3) % SPIN_FRAMES.length]);
        big_view.setImageViewResource(R.id.spin_reel_three, SPIN_FRAMES[(spin_frame + 6) % SPIN_FRAMES.length]);

        configureReelVisibility(big_view);
        configureBetButtons(big_view, bet);

        big_view.setTextViewText(R.id.result_text, spinning ? "REELS SPINNING - BET " + active_bet : result);
        big_view.setTextViewText(R.id.credits_text, "CREDITS\n" + credits);
        big_view.setTextViewText(R.id.jackpot_text, "MAX " + (50 * bet));
        big_view.setTextColor(R.id.result_text, resultTextColor(spinning, result));

        Notification.Builder builder = newNotificationBuilder();
        builder.setSmallIcon(R.drawable.ic_stat_slot)
                .setContentTitle("Notification Bandit")
                .setContentText(spinning ? "SPINNING - BET " + active_bet : result)
                .setContentIntent(buildOpenPendingIntent())
                .setCustomContentView(compact_view)
                .setCustomBigContentView(big_view)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(R.drawable.ic_stat_slot, "PULL LEVER", spin_pending_intent)
                .addAction(R.drawable.ic_stat_slot, "CHANGE BET", next_bet_pending_intent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setStyle(new Notification.DecoratedCustomViewStyle());
        }

        Notification notification = builder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        return notification;
    }

    private int resultTextColor(boolean is_spinning, String result) {
        if (is_spinning) {
            return 0xFFFFD54F;
        }
        if (result == null) {
            return 0xFFFFFFFF;
        }
        if (result.startsWith("JACKPOT")) {
            return 0xFFFFD54F;
        }
        if (result.startsWith("WIN")) {
            return 0xFF7CFF6B;
        }
        if (result.startsWith("LOST") || result.startsWith("BET DECLINED")) {
            return 0xFFFF7777;
        }
        return 0xFFFFFFFF;
    }

    private void configureReelVisibility(RemoteViews view) {
        boolean reel_one_spins = spinning && spin_tick < 10;
        boolean reel_two_spins = spinning && spin_tick < 13;
        boolean reel_three_spins = spinning && spin_tick < 17;

        view.setViewVisibility(R.id.spin_reel_one, reel_one_spins ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.final_reel_one, reel_one_spins ? View.GONE : View.VISIBLE);
        view.setViewVisibility(R.id.spin_reel_two, reel_two_spins ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.final_reel_two, reel_two_spins ? View.GONE : View.VISIBLE);
        view.setViewVisibility(R.id.spin_reel_three, reel_three_spins ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.final_reel_three, reel_three_spins ? View.GONE : View.VISIBLE);

        if (spinning && !reel_one_spins) {
            view.setImageViewResource(R.id.final_reel_one, SYMBOLS[safeIndex(final_reel_one)]);
        }
        if (spinning && !reel_two_spins) {
            view.setImageViewResource(R.id.final_reel_two, SYMBOLS[safeIndex(final_reel_two)]);
        }
        if (spinning && !reel_three_spins) {
            view.setImageViewResource(R.id.final_reel_three, SYMBOLS[safeIndex(final_reel_three)]);
        }
    }

    private void configureBetButtons(RemoteViews view, int selected_bet) {
        configureBetButton(view, R.id.bet_one_button, 1, selected_bet);
        configureBetButton(view, R.id.bet_two_button, 2, selected_bet);
        configureBetButton(view, R.id.bet_five_button, 5, selected_bet);
        configureBetButton(view, R.id.bet_ten_button, 10, selected_bet);
    }

    private void configureBetButton(RemoteViews view, int view_id, int value, int selected_bet) {
        if (value == selected_bet) {
            view.setTextViewText(view_id, "* " + value);
            view.setTextColor(view_id, 0xFFFFD54F);
        } else {
            view.setTextViewText(view_id, String.valueOf(value));
            view.setTextColor(view_id, 0xFFFFFFFF);
        }
    }

    private void updateNotificationSafely() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        try {
            manager.notify(NOTIFICATION_ID, buildCustomNotification());
            saveServiceError("");
        } catch (RuntimeException exception) {
            saveServiceError("Custom notification failed: " + safeMessage(exception));
            try {
                manager.notify(NOTIFICATION_ID, buildBaseNotification());
            } catch (RuntimeException ignored) {
                /* The foreground copy posted in onStartCommand remains active. */
            }
        }
    }

    private Notification.Builder newNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }

    private PendingIntent buildOpenPendingIntent() {
        Intent open_intent = new Intent(this, MainActivity.class);
        open_intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                1200,
                open_intent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag()
        );
    }

    private PendingIntent buildServicePendingIntent(String action, int request_code) {
        Intent intent = new Intent(this, SlotMachineService.class);
        intent.setAction(action);
        return PendingIntent.getService(
                this,
                request_code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag()
        );
    }

    private int safeIndex(int value) {
        if (value < 0 || value >= SYMBOLS.length) {
            return 0;
        }
        return value;
    }

    private int immutableFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE;
        }
        return 0;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_description));
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void saveServiceError(String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVICE_ERROR, message == null ? "" : message)
                .apply();
    }

    public static String getLastServiceError(Context context) {
        if (context == null) {
            return "";
        }
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SERVICE_ERROR, "");
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        if (message == null || message.length() == 0) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    @Override
    public void onDestroy() {
        spin_token++;
        spinning = false;
        animation_handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.zenix.repeatzero.test;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class TargetActivity extends Activity {
    public static final String PREFS = "target_state";
    public static final String KEY_COUNT = "count";
    public static final String KEY_HIDE_B = "hide_b";

    private SharedPreferences prefs;
    private TextView counter;
    private Button buttonB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView title = new TextView(this);
        title.setText("RZ Test Target");
        title.setTextSize(24f);
        root.addView(title, matchWrap());

        counter = new TextView(this);
        counter.setId(R.id.counter);
        counter.setTextSize(22f);
        root.addView(counter, matchWrap());

        root.addView(button("A", R.id.btn_a, v -> increment()), matchWrap());

        buttonB = button("B", R.id.btn_b, v -> increment());
        root.addView(buttonB, matchWrap());

        root.addView(button("PAYMENT", R.id.payment_button, v -> increment()), matchWrap());
        root.addView(button("C", R.id.btn_c, v -> increment()), matchWrap());

        EditText password = new EditText(this);
        password.setId(R.id.password_field);
        password.setHint("Password test");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password, matchWrap());

        root.addView(button("Toggle B", R.id.toggle_b, v -> {
            boolean hide = !prefs.getBoolean(KEY_HIDE_B, false);
            prefs.edit().putBoolean(KEY_HIDE_B, hide).apply();
            applyVisibility();
        }), matchWrap());

        root.addView(button("Reset count", R.id.reset_count, v -> {
            prefs.edit().putInt(KEY_COUNT, 0).apply();
            updateCounter();
        }), matchWrap());

        setContentView(root);
        applyVisibility();
        updateCounter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null) {
            applyVisibility();
            updateCounter();
        }
    }

    private Button button(String text, int id, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setId(id);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private void increment() {
        int next = prefs.getInt(KEY_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_COUNT, next).apply();
        updateCounter();
    }

    private void updateCounter() {
        if (counter != null) counter.setText("Count: " + prefs.getInt(KEY_COUNT, 0));
    }

    private void applyVisibility() {
        if (buttonB != null) {
            buttonB.setVisibility(prefs.getBoolean(KEY_HIDE_B, false) ? View.GONE : View.VISIBLE);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }
}

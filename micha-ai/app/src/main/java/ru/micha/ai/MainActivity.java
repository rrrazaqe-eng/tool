package ru.micha.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String STATE_PREFS = "micha_ai_state";
    private static final String PREV_RESPONSE_ID = "previous_response_id";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SecurePrefs securePrefs;
    private SharedPreferences statePrefs;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        securePrefs = new SecurePrefs(this);
        statePrefs = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        buildUi();
        addBubble("MICHa AI", "Привет! Я готов. Могу отвечать, искать актуальную информацию в интернете и помнить контекст текущего диалога.", false);
        if (securePrefs.loadApiKey() == null) showApiKeyDialog(true);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1117"));
        root.setPadding(dp(14), dp(14), dp(14), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(this);
        title.setText("MICHa AI"); title.setTextColor(Color.WHITE); title.setTextSize(25); title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);
        status = new TextView(this);
        status.setText("Личный ИИ-агент • GPT-6 Luna • web search"); status.setTextColor(Color.parseColor("#AAB4C3")); status.setTextSize(13);
        titleBox.addView(status); header.addView(titleBox);
        Button settings = makeButton("⚙", 54); settings.setContentDescription("Настройки"); settings.setOnClickListener(v -> showSettings()); header.addView(settings);
        root.addView(header);

        scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); scrollParams.topMargin = dp(12); scroll.setLayoutParams(scrollParams);
        messages = new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL); messages.setPadding(0, 0, 0, dp(12)); scroll.addView(messages); root.addView(scroll);

        LinearLayout composer = new LinearLayout(this); composer.setOrientation(LinearLayout.HORIZONTAL); composer.setGravity(Gravity.BOTTOM);
        input = new EditText(this); input.setHint("Напиши задачу…"); input.setHintTextColor(Color.parseColor("#7D8796")); input.setTextColor(Color.WHITE); input.setTextSize(18); input.setMinHeight(dp(58)); input.setMaxLines(5); input.setPadding(dp(16), dp(10), dp(16), dp(10)); input.setBackground(rounded("#161B22", 18)); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); inputParams.rightMargin = dp(8); input.setLayoutParams(inputParams); composer.addView(input);
        send = makeButton("➤", 62); send.setContentDescription("Отправить"); send.setOnClickListener(v -> sendMessage()); composer.addView(send); root.addView(composer);
        setContentView(root);
    }

    private Button makeButton(String text, int sizeDp) {
        Button button = new Button(this); button.setText(text); button.setTextSize(20); button.setTextColor(Color.WHITE); button.setAllCaps(false); button.setGravity(Gravity.CENTER); button.setPadding(0,0,0,0); button.setBackground(rounded("#315EFB",18)); button.setMinWidth(dp(sizeDp)); button.setMinHeight(dp(sizeDp)); button.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))); return button;
    }

    private void sendMessage() {
        String message = input.getText().toString().trim(); if (message.isEmpty()) return;
        String apiKey = securePrefs.loadApiKey(); if (apiKey == null || apiKey.trim().isEmpty()) { showApiKeyDialog(true); return; }
        input.setText(""); addBubble("Ты", message, true); setBusy(true);
        String previous = statePrefs.getString(PREV_RESPONSE_ID, null);
        executor.submit(() -> {
            try {
                OpenAIClient.Result result = new OpenAIClient().send(apiKey, message, previous);
                if (result.responseId != null) statePrefs.edit().putString(PREV_RESPONSE_ID, result.responseId).apply();
                runOnUiThread(() -> { addBubble("MICHa AI", result.text, false); setBusy(false); });
            } catch (Exception e) {
                runOnUiThread(() -> { addBubble("Ошибка", e.getMessage() == null ? "Не удалось получить ответ." : e.getMessage(), false); setBusy(false); });
            }
        });
    }

    private void setBusy(boolean busy) { send.setEnabled(!busy); input.setEnabled(!busy); status.setText(busy ? "MICHa AI думает…" : "Личный ИИ-агент • GPT-6 Luna • web search"); }

    private void addBubble(String author, String text, boolean mine) {
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); wp.bottomMargin = dp(12); wrap.setLayoutParams(wp);
        TextView av = new TextView(this); av.setText(author); av.setTextColor(Color.parseColor("#8B96A8")); av.setTextSize(12); av.setPadding(dp(6),0,dp(6),dp(4)); wrap.addView(av);
        TextView bubble = new TextView(this); bubble.setText(text); bubble.setTextColor(Color.WHITE); bubble.setTextSize(17); bubble.setLineSpacing(0,1.08f); bubble.setPadding(dp(16),dp(12),dp(16),dp(12)); bubble.setBackground(rounded(mine ? "#315EFB" : "#1C2430",20)); Linkify.addLinks(bubble, Linkify.WEB_URLS); bubble.setMovementMethod(LinkMovementMethod.getInstance());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); bp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.86f); bubble.setLayoutParams(bp); wrap.addView(bubble); messages.addView(wrap); scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showSettings() {
        String[] items = {"Изменить API-ключ", "Новый диалог", "Удалить API-ключ", "О приложении"};
        new AlertDialog.Builder(this).setTitle("Настройки MICHa AI").setItems(items, (dialog, which) -> {
            if (which == 0) showApiKeyDialog(false);
            if (which == 1) { statePrefs.edit().remove(PREV_RESPONSE_ID).apply(); messages.removeAllViews(); addBubble("MICHa AI", "Новый диалог начат. Что будем делать?", false); }
            if (which == 2) { securePrefs.clearApiKey(); Toast.makeText(this, "API-ключ удалён", Toast.LENGTH_SHORT).show(); showApiKeyDialog(true); }
            if (which == 3) new AlertDialog.Builder(this).setTitle("MICHa AI v0.1").setMessage("Личный Android ИИ-агент. Использует OpenAI Responses API и может самостоятельно применять веб-поиск, когда это нужно.\n\nAPI-ключ хранится на устройстве в зашифрованном виде через Android Keystore.").setPositiveButton("OK", null).show();
        }).show();
    }

    private void showApiKeyDialog(boolean required) {
        EditText field = new EditText(this); field.setHint("sk-…"); field.setSingleLine(true); field.setTextSize(18); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = dp(18); LinearLayout container = new LinearLayout(this); container.setPadding(pad,dp(8),pad,0); container.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("API-ключ OpenAI").setMessage("Вставь свой API-ключ. Он будет зашифрован Android Keystore и не попадёт в исходный код приложения.").setView(container).setPositiveButton("Сохранить", null).setNegativeButton(required ? "Позже" : "Отмена", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = field.getText().toString().trim(); if (key.isEmpty()) { field.setError("Вставь API-ключ"); return; }
            try { securePrefs.saveApiKey(key); Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show(); dialog.dismiss(); } catch (Exception e) { field.setError("Не удалось сохранить ключ"); }
        })); dialog.show();
    }

    private GradientDrawable rounded(String color, int radiusDp) { GradientDrawable d = new GradientDrawable(); d.setColor(Color.parseColor(color)); d.setCornerRadius(dp(radiusDp)); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}

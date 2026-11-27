package com.mirror.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final String DEFAULT_SERVER = "mirror.poweredandroid579.workers.dev";

    private SharedPreferences prefs;
    private EditText serverInput;
    private EditText passInput;
    private TextView statusView;
    private String message = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("mirror", MODE_PRIVATE);
        buildUi();
        boolean firstTime = prefs.getString("pw", "").isEmpty();
        if (firstTime) {
            message = "Primeiro uso: digite a senha e toque em Salvar e iniciar.";
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        } else {
            handleIntent(getIntent());
        }
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        if (!"mirror".equals(intent.getData().getScheme())) return;
        setIntent(new Intent());
        if (prefs.getString("pw", "").isEmpty()) {
            message = "Primeiro digite a senha e toque em Salvar e iniciar.";
            refreshStatus();
            return;
        }
        requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, CaptureService.class);
            svc.putExtra("data", data);
            startForegroundService(svc);
            message = "Compartilhando a tela. Pode sair do app.";
            statusView.postDelayed(this::refreshStatus, 1500);
            moveTaskToBack(true);
        } else {
            message = "Você não permitiu. Toque em Salvar e iniciar e aceite.";
        }
        refreshStatus();
    }

    private void saveSettings() {
        String server = serverInput.getText().toString().trim()
                .replace("https://", "").replace("http://", "");
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        String pw = passInput.getText().toString();
        prefs.edit().putString("server", server).putString("pw", pw).apply();
    }

    private void refreshStatus() {
        String share = CaptureService.active ? "ativo" : "parado";
        String control = ControlService.instance != null ? "ativado" : "desativado";
        statusView.setText(message + "\n\nCompartilhamento da tela: " + share
                + "\nControle por toque: " + control);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Mirror");
        title.setTextSize(28);
        root.addView(title);

        serverInput = new EditText(this);
        serverInput.setHint("Endereço do servidor");
        serverInput.setSingleLine(true);
        serverInput.setText(prefs.getString("server", DEFAULT_SERVER));
        root.addView(serverInput);

        passInput = new EditText(this);
        passInput.setHint("Senha");
        passInput.setSingleLine(true);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setText(prefs.getString("pw", ""));
        root.addView(passInput);

        Button start = new Button(this);
        start.setText("Salvar e iniciar");
        start.setOnClickListener(v -> {
            saveSettings();
            if (passInput.getText().length() == 0) {
                message = "Digite a senha.";
                refreshStatus();
                return;
            }
            requestCapture();
        });
        root.addView(start);

        Button control = new Button(this);
        control.setText("Ativar controle por toque");
        control.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(control);

        Button stop = new Button(this);
        stop.setText("Parar");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            message = "Parado.";
            statusView.postDelayed(this::refreshStatus, 500);
        });
        root.addView(stop);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(16), 0, 0);
        root.addView(statusView);

        setContentView(scroll);
    }
  }

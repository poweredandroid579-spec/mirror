package com.mirror.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Display;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class CaptureService extends Service {
    static volatile boolean active = false;

    private static final String CHANNEL = "mirror_capture";
    private static boolean webrtcReady = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<IceCandidate> pendingIce = new ArrayList<>();

    private OkHttpClient http;
    private WebSocket ws;
    private boolean running = false;
    private boolean remoteSet = false;
    private String server = "";
    private String password = "";

    private EglBase egl;
    private PeerConnectionFactory factory;
    private SurfaceTextureHelper helper;
    private ScreenCapturerAndroid capturer;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private PeerConnection pc;
    private DataChannel dc;
    private PowerManager.WakeLock wake;

    /** Observador de SDP que não faz nada por padrão. */
    private static class SimpleSdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) cleanup();

        SharedPreferences p = getSharedPreferences("mirror", MODE_PRIVATE);
        server = p.getString("server", "");
        password = p.getString("pw", "");
        Intent data = intent.getParcelableExtra("data");
        if (data == null || server.isEmpty() || password.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground();
        try {
            startCapture(data);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        active = true;
        connect();
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Mirror", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Mirror ativo")
                .setContentText("A tela está disponível para acesso remoto")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        DisplayManager dmgr = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dmgr.getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(dm);
        return dm;
    }

    private void startCapture(Intent data) {
        DisplayMetrics dm = realMetrics();
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int longSide = Math.max(dm.widthPixels, dm.heightPixels);
        int w = Math.min(720, shortSide);
        int h = Math.round(w * (float) longSide / shortSide);
        w = w & ~1;
        h = h & ~1;

        if (!webrtcReady) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                            .createInitializationOptions());
            webrtcReady = true;
        }
        egl = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                        new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
        videoSource = factory.createVideoSource(true);
        helper = SurfaceTextureHelper.create("mirror-capture", egl.getEglBaseContext());
        capturer = new ScreenCapturerAndroid(data, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(() -> stopSelf());
            }
        });
        capturer.initialize(helper, getApplicationContext(), videoSource.getCapturerObserver());
        capturer.startCapture(w, h, 15);
        videoTrack = factory.createVideoTrack("screen", videoSource);
    }

    // ---------------- servidor (ponte) ----------------

    private void connect() {
        if (!running) return;
        if (http == null) {
            http = new OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build();
        }
        String url;
        try {
            url = "https://" + server + "/ws?role=phone&pw=" + URLEncoder.encode(password, "UTF-8");
        } catch (Exception e) {
            return;
        }
        Request req = new Request.Builder().url(url).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket s, String text) {
                main.post(() -> handleSignal(text));
            }

            @Override
            public void onClosed(WebSocket s, int code, String reason) {
                reconnect(s);
            }

            @Override
            public void onFailure(WebSocket s, Throwable t, Response r) {
                reconnect(s);
            }
        });
    }

    private void reconnect(WebSocket s) {
        main.post(() -> {
            if (s != ws) return;
            closePc();
            if (running) main.postDelayed(this::connect, 4000);
        });
    }

    private void send(JSONObject o) {
        WebSocket w = ws;
        if (w != null) w.send(o.toString());
    }

    private void handleSignal(String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            if ("request".equals(type)) {
                wakeScreen();
                startOffer();
            } else if ("answer".equals(type) && pc != null) {
                SessionDescription sdp =
                        new SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp"));
                pc.setRemoteDescription(new SimpleSdp() {
                    @Override
                    public void onSetSuccess() {
                        main.post(() -> {
                            remoteSet = true;
                            for (IceCandidate c : pendingIce) {
                                if (pc != null) pc.addIceCandidate(c);
                            }
                            pendingIce.clear();
                        });
                    }
                }, sdp);
            } else if ("ice".equals(type) && pc != null) {
                JSONObject c = m.getJSONObject("candidate");
                String mid = c.isNull("sdpMid") ? null : c.getString("sdpMid");
                IceCandidate ic = new IceCandidate(mid, c.optInt("sdpMLineIndex", 0),
                        c.getString("candidate"));
                if (remoteSet) {
                    pc.addIceCandidate(ic);
                } else {
                    pendingIce.add(ic);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- WebRTC ----------------

    private void closePc() {
        remoteSet = false;
        pendingIce.clear();
        try {
            if (dc != null) {
                dc.close();
                dc.dispose();
            }
        } catch (Exception ignored) {
        }
        dc = null;
        try {
            if (pc != null) {
                pc.close();
                pc.dispose();
            }
        } catch (Exception ignored) {
        }
        pc = null;
    }

    private void startOffer() {
        closePc();
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478")
                .createIceServer());
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer());
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(servers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState s) { }
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
            public void onIceConnectionReceivingChange(boolean b) { }
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }

            public void onIceCandidate(IceCandidate c) {
                try {
                    JSONObject cand = new JSONObject();
                    cand.put("candidate", c.sdp);
                    cand.put("sdpMid", c.sdpMid);
                    cand.put("sdpMLineIndex", c.sdpMLineIndex);
                    JSONObject o = new JSONObject();
                    o.put("type", "ice");
                    o.put("candidate", cand);
                    send(o);
                } catch (Exception ignored) {
                }
            }

            public void onIceCandidatesRemoved(IceCandidate[] c) { }
            public void onAddStream(org.webrtc.MediaStream s) { }
            public void onRemoveStream(org.webrtc.MediaStream s) { }
            public void onDataChannel(DataChannel d) { }
            public void onRenegotiationNeeded() { }
            public void onAddTrack(org.webrtc.RtpReceiver r, org.webrtc.MediaStream[] s) { }
        });
        if (pc == null) return;

        pc.addTrack(videoTrack, Collections.singletonList("mirror"));
        dc = pc.createDataChannel("ctl", new DataChannel.Init());
        dc.registerObserver(new DataChannel.Observer() {
            public void onBufferedAmountChange(long previous) { }
            public void onStateChange() { }

            public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer bb = buffer.data;
                byte[] bytes = new byte[bb.remaining()];
                bb.get(bytes);
                final String s = new String(bytes, StandardCharsets.UTF_8);
                main.post(() -> onControl(s));
            }
        });

        final PeerConnection mine = pc;
        mine.createOffer(new SimpleSdp() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                mine.setLocalDescription(new SimpleSdp() {
                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("type", "offer");
                            o.put("sdp", sdp.description);
                            send(o);
                        } catch (Exception ignored) {
                        }
                    }
                }, sdp);
            }
        }, new MediaConstraints());
    }

    // ---------------- controle ----------------

    private void onControl(String s) {
        try {
            JSONObject o = new JSONObject(s);
            if ("key".equals(o.optString("t")) && "wake".equals(o.optString("k"))) {
                wakeScreen();
                return;
            }
            wakeScreen();
            ControlService.handle(o);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (wake != null && wake.isHeld()) wake.release();
            wake = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "mirror:wake");
            wake.acquire(20000);
        } catch (Exception ignored) {
        }
    }

    // ---------------- encerramento ----------------

    private void cleanup() {
        running = false;
        active = false;
        main.removeCallbacksAndMessages(null);
        try {
            if (ws != null) ws.close(1000, "bye");
        } catch (Exception ignored) {
        }
        ws = null;
        closePc();
        try {
            if (capturer != null) {
                capturer.stopCapture();
                capturer.dispose();
            }
        } catch (Exception ignored) {
        }
        capturer = null;
        try {
            if (helper != null) helper.dispose();
            if (videoTrack != null) videoTrack.dispose();
            if (videoSource != null) videoSource.dispose();
            if (factory != null) factory.dispose();
            if (egl != null) egl.release();
        } catch (Exception ignored) {
        }
        helper = null;
        videoTrack = null;
        videoSource = null;
        factory = null;
        egl = null;
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        cleanup();
        stopForeground(true);
        super.onDestroy();
    }
  }

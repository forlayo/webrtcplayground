package com.forlayo.webrtc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.SurfaceViewRenderer;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class MainActivity extends AppCompatActivity implements ISignaling {

    private static final String TAG = MainActivity.class.getCanonicalName();

    private static final int CAPTURE_PERMISSION_REQUEST_CODE = 2;
    IWebRTCCall webRTCCall;
    SurfaceViewRenderer remoteCamera;
    SurfaceViewRenderer localCamera;
    SurfaceViewRenderer remoteScreen;
    Button btnCall;
    Button btnClose;
    private Intent mediaProjectionPermissionResultData;
    private Socket mSocket;
    private Gson gson;
    private Emitter.Listener onNewMessage = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            runOnUiThread(() -> {
                JSONObject data = (JSONObject) args[0];
                Log.d(TAG, "onNewMessage() RCV :: " + data.toString());
                webRTCCall.onSignalingReceived(gson.fromJson(data.toString(), SdpSignaling.class));
            });
        }
    };
    private Emitter.Listener onConnect = args -> runOnUiThread(() -> {
        Log.i(TAG, "onConnect() ");
        Toast.makeText(getApplicationContext(), "Connected!", Toast.LENGTH_LONG).show();
    });
    private Emitter.Listener onDisconnect = args -> runOnUiThread(() -> {
        Log.i(TAG, "onDisconnect() ");
        Toast.makeText(getApplicationContext(), "Disconnected!", Toast.LENGTH_LONG).show();
    });
    private Emitter.Listener onConnectError = args -> runOnUiThread(() -> {
        Log.e(TAG, "onConnectError() Error connecting: " + ((Exception) args[0]).getMessage());
        ((Exception) args[0]).printStackTrace();
        Toast.makeText(getApplicationContext(), "Error connecting!", Toast.LENGTH_LONG).show();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        gson = new Gson();

        remoteCamera = findViewById(R.id.svr_remote_camera);
        localCamera = findViewById(R.id.svr_local_camera);
        remoteScreen = findViewById(R.id.svr_remote_screen);

        webRTCCall = new WebRTCCall(this, this, remoteCamera, remoteScreen, localCamera);

        btnCall = findViewById(R.id.btn_call);
        btnCall.setOnClickListener(v -> {

            if (mediaProjectionPermissionResultData == null) {
                Toast.makeText(getApplicationContext(), "Need first to accept screen capturing!", Toast.LENGTH_LONG).show();
                startScreenCapture();
                return;
            }

            webRTCCall.call();

        });

        btnClose = findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> webRTCCall.hang());

        try {
            mSocket = IO.socket("https://rtcsignaling-forlayo.c9users.io:8080");
            mSocket.on(Socket.EVENT_CONNECT, onConnect);
            mSocket.on(Socket.EVENT_DISCONNECT, onDisconnect);
            mSocket.on(Socket.EVENT_CONNECT_ERROR, onConnectError);
            mSocket.on(Socket.EVENT_CONNECT_TIMEOUT, onConnectError);
            mSocket.on("message", onNewMessage);
            mSocket.connect();

        } catch (URISyntaxException e) {
            Log.e(TAG, "URLSyntax exception!");
        }

        startScreenCapture();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void startScreenCapture() {
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getApplication().getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(), CAPTURE_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode != CAPTURE_PERMISSION_REQUEST_CODE)
            return;

        if (resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "Screen capturing is needed, please accept the request.");
            Toast.makeText(getApplicationContext(), "Screen capturing is needed, please accept the request.", Toast.LENGTH_LONG).show();
            return;
        }

        mediaProjectionPermissionResultData = data;
        webRTCCall.setScreenCaptureProjectionData(data);
    }

    @Override
    public void toSend(SdpSignaling cmd) {
        try {
            JSONObject obj = new JSONObject(gson.toJson(cmd));
            mSocket.emit("message", obj);
            Log.d(TAG, "toSend() SENT :: " + obj.toString());

        } catch (JSONException e) {
            Log.e(TAG, "toSend() exception sending message");
            e.printStackTrace();
        }
    }

}

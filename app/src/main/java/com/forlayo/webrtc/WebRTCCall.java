package com.forlayo.webrtc;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.- Get the intent of screen capture permission.
 * 2.- Pass it to this class.
 * 3.- Setup to pass signaling message to this class at onSignalingReceived() and to send what this produces at
 * sendSignalingMessage, through signaling channel.
 * 4.- Execute call() or wait to receive an offer from other side.
 */
public class WebRTCCall implements IWebRTCCall {

    private static final String TAG = WebRTCCall.class.getCanonicalName();
    private final ProxyVideoSink remoteCameraProxyRenderer = new ProxyVideoSink();
    private final ProxyVideoSink remoteScreenProxyRenderer = new ProxyVideoSink();
    private final ProxyVideoSink localProxyVideoSink = new ProxyVideoSink();
    private VideoBundle screenVideoBundle;
    private VideoBundle cameraVideoBundle;
    private EglBase rootEglBase;
    private PeerConnectionFactory factory;
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteCameraVideoView;
    private SurfaceViewRenderer remoteScreenVideoView;
    private MediaConstraints audioConstraints;
    private MediaConstraints sdpConstraints;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private DataChannel.Observer dataChannelObserver;
    private List<PeerConnection.IceServer> peerIceServers = new ArrayList<>();
    private List<IceCandidate> queuedRemoteCandidates;
    private WeakReference<Context> ctxWeak;
    private ISignaling signaling;
    private Intent mediaProjectionPermissionResultData;
    private SdpSignaling callWaitingForPermission;
    /**
     * It's needed to ask for screen capture permission and pass the intent received as a result
     * of successfully allow it.
     *
     * @param context
     * @param signaling
     */
    public WebRTCCall(Context context,
                      ISignaling signaling,
                      SurfaceViewRenderer remoteCamera,
                      SurfaceViewRenderer remoteScreen,
                      SurfaceViewRenderer localCamera) {

        peerIceServers.add(
                PeerConnection.IceServer
                        .builder("stun:stun.l.google.com:19302")
                        .createIceServer()
        );

        this.signaling = signaling;
        this.ctxWeak = new WeakReference<>(context);

        this.localVideoView = localCamera;
        this.remoteCameraVideoView = remoteCamera;
        this.remoteScreenVideoView = remoteScreen;

    }

    /**
     * Resets all WebRTC objects and creates PeerConnection.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void initWebRTC() {

        Log.d(TAG, "initWebRTC()");

        hang(); // Being sure not already initialized or in a call.

        rootEglBase = EglBase.create();

        localVideoView.init(rootEglBase.getEglBaseContext(), null);
        remoteCameraVideoView.init(rootEglBase.getEglBaseContext(), null);
        remoteScreenVideoView.init(rootEglBase.getEglBaseContext(), null);

        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(ctxWeak.get().getApplicationContext())
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);


        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(
                rootEglBase.getEglBaseContext(), true, true);

        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new DefaultVideoDecoderFactory(
                rootEglBase.getEglBaseContext());

        final AudioDeviceModule audioDeviceModule = JavaAudioDeviceModule.builder(ctxWeak.get())
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();

        factory =
                PeerConnectionFactory.builder()
                        .setOptions(options)
                        .setAudioDeviceModule(audioDeviceModule)
                        .setVideoDecoderFactory(defaultVideoDecoderFactory)
                        .setVideoEncoderFactory(defaultVideoEncoderFactory)
                        .createPeerConnectionFactory();

        // Not sure why, but this is the way to proceed.
        audioDeviceModule.release();

        screenVideoBundle = new VideoBundle();
        screenVideoBundle.name = "screen";
        screenVideoBundle.videoCapturer = createScreenCapturer();
        if (screenVideoBundle.videoCapturer != null) {
            screenVideoBundle.localVideoTrack = createVideoTrack(
                    ctxWeak.get().getApplicationContext(),
                    screenVideoBundle);
        } else {
            Log.e(TAG, "Not able to create a capturer for the screen!");
        }

        cameraVideoBundle = new VideoBundle();
        cameraVideoBundle.name = "camera";
        cameraVideoBundle.videoCapturer = createCameraCapturer(ctxWeak.get());
        if (cameraVideoBundle.videoCapturer != null) {
            cameraVideoBundle.localVideoTrack = createVideoTrack(
                    ctxWeak.get().getApplicationContext(),
                    cameraVideoBundle);
        }

        audioConstraints = new MediaConstraints();
        localAudioTrack = createAudioTrack();

        sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        localVideoView.setZOrderMediaOverlay(true); // Otherwise remote video will overlay local one.
        localVideoView.setEnableHardwareScaler(true);
        localVideoView.setMirror(true);
        localProxyVideoSink.setTarget(localVideoView);
        if (cameraVideoBundle.localVideoTrack != null)
            cameraVideoBundle.localVideoTrack.addSink(localProxyVideoSink);

        remoteCameraVideoView.setEnableHardwareScaler(true);

        remoteScreenVideoView.setEnableHardwareScaler(false);
        remoteScreenVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);

        remoteScreenVideoView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Log.d(TAG, "onTouch() Event:" + event.getAction() + " X:" + event.getX() + " Y:" + event.getY());

                if(dataChannel!=null) {
                    String touchStr = "Touch at  X:" + event.getX() + " Y:" + event.getY();
                    ByteBuffer buffer = ByteBuffer.wrap(touchStr.getBytes());
                    dataChannel.send(new DataChannel.Buffer(buffer, false));
                }

                return false;
            }
        });

        dataChannelObserver = new DataChannel.Observer() {

            @Override
            public void onBufferedAmountChange(long l) {
                if (dataChannel != null)
                    Log.d(TAG, "onBufferedAmountChange(): " + dataChannel.label() + " amount: " + l);
                else
                    Log.d(TAG, "onBufferedAmountChange(), null dataChannel");
            }

            @Override
            public void onStateChange() {
                if (dataChannel != null)
                    Log.d(TAG, "onStateChange(): " + dataChannel.label() + ": " + dataChannel.state());
                else
                    Log.d(TAG, "onStateChange(): CLOSED (null datachannel)");
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary) {
                    Log.d(TAG, "Received binary msg over " + dataChannel);
                    return;
                }
                try {
                    ByteBuffer data = buffer.data;
                    final byte[] bytes = new byte[data.capacity()];
                    data.get(bytes);
                    String strData = new String(bytes, Charset.forName("UTF-8"));
                    Log.d(TAG, "Got msg: " + strData + " over " + dataChannel);

                    runOnUiThread(() -> {
                        Toast.makeText(ctxWeak.get(), strData, Toast.LENGTH_SHORT).show();
                    });
                }catch (Exception ex){
                    /*Who cares*/
                }
            }
        };

        createPeerConnection();
    }

    private AudioTrack createAudioTrack() {
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("camera-audio", audioSource);
        localAudioTrack.setEnabled(true);
        return localAudioTrack;
    }

    private VideoTrack createVideoTrack(Context appContext, VideoBundle bundle) {
        bundle.surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
        bundle.videoSource = factory.createVideoSource(bundle.videoCapturer.isScreencast());
        bundle.videoCapturer.initialize(bundle.surfaceTextureHelper, appContext, bundle.videoSource.getCapturerObserver());
        bundle.videoCapturer.startCapture(1280, 720, 30);

        bundle.localVideoTrack = factory.createVideoTrack(bundle.name, bundle.videoSource);
        bundle.localVideoTrack.setEnabled(true);
        return bundle.localVideoTrack;
    }

    private void createDataChannel() {
        if (peerConnection != null) {
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            init.maxRetransmits = -1;
            init.maxRetransmitTimeMs = -1;
            init.id = -1;

            dataChannel = peerConnection.createDataChannel("backcontrol", init);
            dataChannel.registerObserver(dataChannelObserver);
        }
    }

    /**
     * This method is intended to be called after initWebRTC(), as it needs having webrtc stuff ready.
     */
    private void createPeerConnection() {
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(peerIceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        // Use ECDSA encryption.
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        peerConnection = factory.createPeerConnection(rtcConfig, new CustomPeerConnectionObserver("localPeerCreation") {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                onIceCandidateReceived(iceCandidate);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                gotRemoteStream(mediaStream);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                super.onConnectionChange(newState);
                Log.d(TAG, "PeerConnectionState: " + newState);

                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    Log.w(TAG, "Connected!");

                } else if (newState == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    // This is like your status, don't expect on using this like connection lost or so.

                } else if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    Log.e(TAG, "DTLS connection failed.");
                    hang();
                }

            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                super.onIceConnectionChange(iceConnectionState);
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);

                // FAILED -> All channels are closed, connection lost or other party has closed the connection.
                // CLOSED -> You've closed.
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED ||
                        iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    runOnUiThread(() -> {
                        Toast.makeText(ctxWeak.get(), ctxWeak.get().getString(R.string.support_toast_disconnected), Toast.LENGTH_SHORT).show();
                        hang();
                    });

                } else if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    runOnUiThread(() -> Toast.makeText(ctxWeak.get(), ctxWeak.get().getString(R.string.support_toast_connected), Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onDataChannel(DataChannel channel) {
                super.onDataChannel(channel);
                Log.d(TAG, "New Data channel " + channel.label());
                dataChannel = channel;
                dataChannel.registerObserver(dataChannelObserver);
            }
        });

        addStreamToLocalPeer();
    }

    private void addStreamToLocalPeer() {
        //creating local mediastream
        MediaStream stream = factory.createLocalMediaStream("RemoteSupport");
        stream.addTrack(screenVideoBundle.localVideoTrack);
        if (cameraVideoBundle.localVideoTrack != null)
            stream.addTrack(cameraVideoBundle.localVideoTrack);
        if (localAudioTrack != null)
            stream.addTrack(localAudioTrack);
        peerConnection.addStream(stream);
    }

    private void gotRemoteStream(MediaStream stream) {
        Log.d(TAG, "gotRemoteStream() " + stream.getId());

        //we have remote video stream, add camera to renderer.
        for (VideoTrack videoTrack : stream.videoTracks) {
            Log.d(TAG, "videoTrack:" + videoTrack.id());
            if (videoTrack.id().equalsIgnoreCase(cameraVideoBundle.name)) {
                runOnUiThread(() -> {
                    try {
                        videoTrack.addSink(remoteCameraProxyRenderer);
                        remoteCameraProxyRenderer.setTarget(remoteCameraVideoView);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

            } else if (videoTrack.id().equalsIgnoreCase(screenVideoBundle.name)) {
                runOnUiThread(() -> {
                    try {
                        videoTrack.addSink(remoteScreenProxyRenderer);
                        remoteScreenProxyRenderer.setTarget(remoteScreenVideoView);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        }

    }

    @Override
    public void setScreenCaptureProjectionData(Intent mediaProjectionData) {
        Log.d(TAG, "setScreenCaptureProjectionData()");
        this.mediaProjectionPermissionResultData = mediaProjectionData;

        if (callWaitingForPermission == null) {
            return;
        }
        onOfferReceived(callWaitingForPermission);
    }

    @Override
    public void call() {
        initWebRTC();
        doOffer();
    }

    @Override
    public void declineSupportCall() {
        if (callWaitingForPermission == null) {
            Log.e(TAG, "callWaitingForPermission is null! can't decline null stuff -_-U");
            return;
        }
        if (signaling == null) {
            Log.e(TAG, "Signaling is null! can't tell other party about declining!");
            return;
        }
        callWaitingForPermission = null;
    }

    /**
     * Closes and frees all WebRTC objects.
     */
    @Override
    public void hang() {

        // PeerConnection null means call is already closed or not initiated.
        if (peerConnection == null) {
            return;
        }

        queuedRemoteCandidates = null;

        remoteCameraProxyRenderer.setTarget(null);
        remoteScreenProxyRenderer.setTarget(null);
        localProxyVideoSink.setTarget(null);

        remoteCameraVideoView.release();
        remoteScreenVideoView.release();
        localVideoView.release();

        if (dataChannel != null) {
            dataChannel.dispose();
            dataChannel = null;
        }

        peerConnection.close();
        peerConnection.dispose();
        peerConnection = null;

        if (audioSource != null)
            audioSource.dispose();

        disposeBundle(cameraVideoBundle);
        disposeBundle(screenVideoBundle);

        factory.dispose();
        rootEglBase.release();
    }

    private void disposeBundle(VideoBundle bundle) {
        if (bundle.videoCapturer == null) {
            return;
        }
        try {
            bundle.videoCapturer.stopCapture();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        bundle.videoCapturer.dispose();
        bundle.videoSource.dispose();
        bundle.surfaceTextureHelper.dispose();
    }

    private void doOffer() {
        if (peerConnection == null) {
            Log.e(TAG, "Trying to do doOffer() without having peer connection! O_O; missed to call initWebRTC()?");
            return;
        }
        createDataChannel();
        peerConnection.createOffer(new CustomSdpObserver("localCreateOffer") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);

                Log.d(TAG, "doOffer() - onCreateSuccess " + sessionDescription.toString());

                //Can't use CustomSdpObserver with overriding here, webrtc is using this interface here in a
                //weird manner and your going to get an exception.
                peerConnection.setLocalDescription(
                        new SdpObserver() {
                            @Override
                            public void onCreateSuccess(SessionDescription sessionDescription) {
                                // Not applicable
                            }

                            @Override
                            public void onCreateFailure(String s) {
                                // Not applicable
                            }

                            @Override
                            public void onSetSuccess() {

                                Log.d(TAG, "doOffer - setLocalDescription success");

                                // Sending OFFER to the other party
                                SdpSignaling signalingCmd = new SdpSignaling();
                                signalingCmd.setType("offer");
                                signalingCmd.setSdp(sessionDescription.description);

                                if (signaling == null) {
                                    Log.e(TAG, "Signaling is null! can't send OFFER!");
                                    return;
                                }

                                // Ask to send it back
                                signaling.toSend(signalingCmd);

                            }

                            @Override
                            public void onSetFailure(String s) {
                                Log.e(TAG, "doOffer - setLocalDescription failure");

                            }
                        },
                        sessionDescription);

            }
        }, sdpConstraints);
    }

    private void onOfferReceived(SdpSignaling signalingCmd) {
        Log.d(TAG, "onOfferReceived():" + signalingCmd.toJsonString());
        runOnUiThread(() -> {

            // PeerConnection null means call is already closed or not initiated.
            if (peerConnection != null) {
                Log.w(TAG, "Getting OFFER having a call, this will end the current one");
                runOnUiThread(() -> {
                    Toast.makeText(ctxWeak.get(), ctxWeak.get().getString(R.string.support_toast_called_having_a_call), Toast.LENGTH_SHORT).show();
                });
            }

            initWebRTC();
            //Can't use CustomSdpObserver with overriding here, webrtc is using this interface here in a
            //weird manner and your going to get an exception.
            peerConnection.setRemoteDescription(
                    new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sessionDescription) {
                            // Not applicable
                        }

                        @Override
                        public void onCreateFailure(String s) {
                            // Not applicable
                        }

                        @Override
                        public void onSetSuccess() {
                            Log.d(TAG, "peerConnection.setRemoteDescription: success");
                            doAnswer(signalingCmd);
                        }

                        @Override
                        public void onSetFailure(String s) {
                            Log.e(TAG, "peerConnection.setRemoteDescription: failure");
                        }
                    },
                    new SessionDescription(SessionDescription.Type.OFFER, signalingCmd.getSdp()));

        });
    }

    private void doAnswer(SdpSignaling offerCmd) {
        Log.d(TAG, "doAnswer()");

        if (peerConnection == null) {
            Log.e(TAG, "Trying to do doAnswer() without having peer connection! O_O");
            return;
        }
        peerConnection.createAnswer(new CustomSdpObserver("localCreateAns") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);

                Log.d(TAG, "doAnswer() - onCreateSuccess" + sessionDescription.toString());

                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {
                        // Not applicable
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        // Not applicable
                    }

                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "doAnswer() - setLocalDescription success ");
                        drainCandidates();

                        // Sending ANSWER to the other party
                        SdpSignaling signalingCmd = new SdpSignaling();
                        signalingCmd.setType("answer");
                        signalingCmd.setSdp(sessionDescription.description);
                        if (signaling == null) {
                            Log.e(TAG, "Signaling is null! can't send ANSWER back!");
                            return;
                        }

                        // Ask to send it back
                        signaling.toSend(signalingCmd);

                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "doAnswer() - setLocalDescription failure ");

                    }
                }, sessionDescription);


            }
        }, new MediaConstraints());
    }

    private void onAnswerReceived(SdpSignaling signalingCmd) {
        Log.d(TAG, "onAnswerReceived()");

        if (peerConnection == null) {
            Log.e(TAG, "Received an unexpected ANSWER! O_O, discarding");
            return;
        }
        peerConnection.setRemoteDescription(
                new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {
                        // Not applicable
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        // Not applicable
                    }

                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "onAnswerReceived.setRemoteDescription: success");
                        drainCandidates();
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "onAnswerReceived.setRemoteDescription: failure");
                    }
                }
                ,
                new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(signalingCmd.getType().toLowerCase()),
                        signalingCmd.getSdp()));

    }

    private void onIceCandidateReceived(IceCandidate iceCandidate) {
        //we have received ice candidate. We can set it to the other peer.
        Log.d(TAG, "onIceCandidateReceived() " + iceCandidate.toString());

        //Sending the CANDIDATE to the other party
        SdpSignaling signalingCmd = new SdpSignaling();
        signalingCmd.setType("candidate");
        signalingCmd.setSdpMid(iceCandidate.sdpMid);
        signalingCmd.setSdpMLineIndex(iceCandidate.sdpMLineIndex);
        signalingCmd.setSdp(iceCandidate.sdp);

        if (signaling == null) {
            Log.e(TAG, "Signaling is null! can't send CANDIDATE back!");
            return;
        }

        // Ask to send it back
        signaling.toSend(signalingCmd);
    }

    private void onRemoteIceCandidateReceived(SdpSignaling signalingCmd) {
        Log.d(TAG, "onRemoteIceCandidateReceived() " + signalingCmd.toJsonString());

        if (peerConnection == null) {
            if (queuedRemoteCandidates == null) {
                Log.w(TAG, "Queue of candidates is null!");
                return;
            }
            queuedRemoteCandidates.add(new IceCandidate(signalingCmd.getSdpMid(),
                    signalingCmd.getSdpMLineIndex(),
                    signalingCmd.getSdp()));
        } else {
            peerConnection.addIceCandidate(
                    new IceCandidate(signalingCmd.getSdpMid(),
                            signalingCmd.getSdpMLineIndex(),
                            signalingCmd.getSdp()));
        }

    }

    private void drainCandidates() {
        Log.d(TAG, "drainCandidates()");
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "drainCandidates() adding " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                peerConnection.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
        }
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Log.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Log.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private VideoCapturer createCameraCapturer(Context context) {
        VideoCapturer videoCapturer;

        if (Camera2Enumerator.isSupported(context)) {
            Log.d(TAG, "Creating capturer using camera2 API.");
            videoCapturer = createCameraCapturer(new Camera2Enumerator(context));
        } else {
            Log.d(TAG, "Creating capturer using camera1 API.");
            videoCapturer = createCameraCapturer(new Camera1Enumerator(false));
        }

        return videoCapturer;
    }

    @TargetApi(21)
    private VideoCapturer createScreenCapturer() {
        if (mediaProjectionPermissionResultData == null) {
            Log.e(TAG, "User didn't give permission to capture the screen.");
            return null;
        }
        return new ScreenCapturerAndroid(
                mediaProjectionPermissionResultData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.e(TAG, "User revoked permission to capture the screen.");
            }
        });
    }

    @Override
    public void onSignalingReceived(SdpSignaling signalingCmd) {

        if (signalingCmd.getType().equalsIgnoreCase("offer")) {
            // Do not call here onOfferDirectly or we're going to have an issue of not
            // asking for accepting the call for second and subsequent times.
            callWaitingForPermission = signalingCmd;
            queuedRemoteCandidates = new ArrayList<>();

            if (mediaProjectionPermissionResultData != null) {
                onOfferReceived(callWaitingForPermission);
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(ctxWeak.get(), "Call received but not accepted to share screen.", Toast.LENGTH_SHORT).show();
                    hang();
                });
            }

        } else if (signalingCmd.getType().equalsIgnoreCase("answer")) {
            onAnswerReceived(signalingCmd);

        } else if (signalingCmd.getType().equalsIgnoreCase("candidate")) {
            onRemoteIceCandidateReceived(signalingCmd);
        }

    }

    private void runOnUiThread(Runnable toRun) {
        Handler h = new Handler(ctxWeak.get().getMainLooper());
        h.post(toRun);
    }

    private static class ProxyVideoSink implements VideoSink {
        private VideoSink target;

        @Override
        synchronized public void onFrame(VideoFrame frame) {
            if (target == null) {
                return;
            }

            target.onFrame(frame);
        }

        synchronized public void setTarget(VideoSink target) {
            this.target = target;
        }
    }

    class VideoBundle {
        String name;
        VideoCapturer videoCapturer;
        VideoSource videoSource;
        VideoTrack localVideoTrack;
        SurfaceTextureHelper surfaceTextureHelper;
    }

}

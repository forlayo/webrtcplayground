package com.forlayo.webrtc;

import android.content.Intent;

public interface IWebRTCCall {
    void setScreenCaptureProjectionData(Intent mediaProjectionData);

    void declineSupportCall();

    void call();

    void hang();

    void onSignalingReceived(SdpSignaling signalingCmd);
}

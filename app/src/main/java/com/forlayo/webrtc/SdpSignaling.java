package com.forlayo.webrtc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import lombok.Getter;
import lombok.Setter;

public class SdpSignaling {

    @SerializedName("type")
    @Setter
    @Getter
    private String type;
    @SerializedName("sdp")
    @Setter
    @Getter
    private String sdp;

    // For ice candidates
    @SerializedName("sdpMid")
    @Setter
    @Getter
    private String sdpMid;
    @SerializedName("sdpMLineIndex")
    @Setter
    @Getter
    private Integer sdpMLineIndex;

    public String toJsonString() {
        Gson gson = new GsonBuilder().create();
        return gson.toJson(this);
    }

}

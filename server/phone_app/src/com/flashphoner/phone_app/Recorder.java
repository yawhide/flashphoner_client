package com.flashphoner.phone_app;

import com.flashphoner.sdk.rtmp.Config;
import com.flashphoner.sdk.rtmp.IRtmpClient;
import com.flashphoner.sdk.softphone.SoftphoneCallState;
import com.flashphoner.sdk.call.ISipCall;
import com.wowza.wms.livestreamrecord.model.ILiveStreamRecord;
import com.wowza.wms.livestreamrecord.model.LiveStreamRecorderFLV;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.MediaStreamMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: Alexey
 * Date: 10.02.14
 * Time: 18:07
 * To change this template use File | Settings | File Templates.
 */
public class Recorder {

    private static Map<String, String> streamMap = new HashMap<String, String>();

    private static Set<String> recordingCallIds = new HashSet<String>();

    static {
        String streamMapSetting = ClientConfig.getInstance().getProperty("stream_map");
        if (streamMapSetting != null && !streamMapSetting.isEmpty()) {
            streamMap = Utils.parseStreamMap(streamMapSetting);
        }
    }

    private static Logger log = LoggerFactory.getLogger("Recorder");
    private static Logger isRecordingAllowedLog = LoggerFactory.getLogger("isRecordingAllowed.Recorder");

    protected static Map<IMediaStream, ILiveStreamRecord> recordingMap = new HashMap<IMediaStream, ILiveStreamRecord>();


    protected IRtmpClient rtmpClient;

    public Recorder(IRtmpClient rtmpClient) {
        this.rtmpClient = rtmpClient;
    }

    protected void startRecording() {

        log.info("startRecording rtmpClientID: " + rtmpClient.getClient().getClientId());

        String login = rtmpClient.getRtmpClientConfig().getAuthenticationName();

        ISipCall call = rtmpClient.getSoftphone().getCurrentCall();
        if (call == null || !SoftphoneCallState.TALK.equals(call.getState())) {
            log.info("Current established call is not found for login: " + login + " call: " + call);
            return;
        }

        String callId = call.getId();
        String caller = call.getCaller();
        String callee = call.getCallee();
        log.info("talk callId: " + callId + " login: " + login + " caller: " + caller + " callee: " + callee);


        byte recordingBitMask = findRecordingBitMask(login + "@" + rtmpClient.getRtmpClientConfig().getDomain());
        if (recordingBitMask == 0) {
            if (log.isDebugEnabled()) {
                log.debug("No recordingBitMask for login: " + login);
            }
            return;
        }

        String folder = caller + "-" + callee + "-" + callId;
        String folderPath = Config.WOWZA_HOME + "/content/" + folder;
        synchronized (recordingMap) {
            if (!new File(folderPath).exists()) {
                if (!(new File(folderPath).mkdir())) {
                    log.error("Can't create dir: " + folderPath);
                    return;
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Recording folder is ready: " + folderPath);
        }

        MediaStreamMap mediaStreamMap = rtmpClient.getClient().getAppInstance().getStreams();
        List<IMediaStream> streams = mediaStreamMap.getStreams();

        for (IMediaStream stream : streams) {
            //check stream clientID, can be -1
            if (stream.getClientId() == rtmpClient.getClient().getClientId()) {
                //check if recording allowed
                if (isRecordingAllowed(stream, callId, caller, callee, recordingBitMask)) {
                    record(stream, folderPath);
                } else {
                    log.info("Recording is not allowed: caller: " + caller + " callee: " + callee + " stream: " + stream.getName() + " streamObj: " + stream + " recordingBitMask: " + recordingBitMask);
                }
            }
        }


    }

    private boolean isRecordingAllowed(IMediaStream stream, String callId, String caller, String callee, byte recordingBitMask) {

        boolean recordCallerOutgoingAudioVideo = (recordingBitMask >> 5 & 0x01) == 1;
        boolean recordCallerIncomingAudio = (recordingBitMask >> 4 & 0x01) == 1;
        boolean recordCallerIncomingVideo = (recordingBitMask >> 3 & 0x01) == 1;

        boolean recordCalleeOutgoingAudioVideo = (recordingBitMask >> 2 & 0x01) == 1;
        boolean recordCalleeIncomingAudio = (recordingBitMask >> 1 & 0x01) == 1;
        boolean recordCalleeIncomingVideo = (recordingBitMask >> 0 & 0x01) == 1;

        isRecordingAllowedLog.debug("isRecordingAllowed stream: " + stream.getName() + " callId: " + callId + " caller: " + caller + "callee: " + callee + " mask: " + recordingBitMask);
        isRecordingAllowedLog.debug("isRecordingAllowed for CALLER recordCallerOutgoingAudioVideo: " + recordCallerOutgoingAudioVideo + " recordCallerIncomingAudio: " + recordCallerIncomingAudio + " recordCallerIncomingVideo: " + recordCallerIncomingVideo);
        isRecordingAllowedLog.debug("isRecordingAllowed for CALLEE recordCalleeOutgoingAudioVideo: " + recordCalleeOutgoingAudioVideo + " recordCalleeIncomingAudio: " + recordCalleeIncomingAudio + " recordCalleeIncomingVideo: " + recordCalleeIncomingVideo);

        String streamName = stream.getName();
        if (stream.getStreamType().equalsIgnoreCase("live")) {
            if (streamName.indexOf("VIDEO_INCOMING_" + caller) != -1 && recordCallerIncomingVideo) {
                return true;
            } else if (streamName.indexOf("INCOMING_" + caller) != -1 && recordCallerIncomingAudio) {
                return true;
            } else if (streamName.indexOf("VIDEO_INCOMING_" + callee) != -1 && recordCalleeIncomingVideo) {
                return true;
            } else if (streamName.indexOf("INCOMING_" + callee) != -1 && recordCalleeIncomingAudio) {
                return true;
            }
        } else if (stream instanceof PhoneRtmp2VoipStream) {
            if (streamName.equalsIgnoreCase(caller + "_" + callId) && recordCallerOutgoingAudioVideo) {
                return true;
            }
            if (streamName.equalsIgnoreCase(callee + "_" + callId) && recordCalleeOutgoingAudioVideo) {
                return true;
            }
        }

        return false;

    }

    private void record(IMediaStream recordingStream, String folderPath) {
        synchronized (recordingMap) {
            if (!recordingMap.containsKey(recordingStream)) {
                String filePath = folderPath + "/" + recordingStream.getName() + ".flv";
                boolean append = true;
                LiveStreamRecorderFLV liveStreamRecorderFLV = new LiveStreamRecorderFLV();
                liveStreamRecorderFLV.setStartOnKeyFrame(Config.getInstance().getBooleanProperty("start_recording_on_key_frame", true));
                liveStreamRecorderFLV.startRecording(recordingStream, filePath, append, new HashMap<String, Object>(), ILiveStreamRecord.SPLIT_ON_DISCONTINUITY_NEVER);
                recordingMap.put(recordingStream, liveStreamRecorderFLV);
                log.info("Starting recording stream: " + recordingStream + " filePath: " + filePath + " append: " + append);
            }
        }
    }

    private byte findRecordingBitMask(String login) {
        byte bitMask = 0;
        String recordMap = Config.getInstance().getProperty("record_map");
        if (recordMap != null && !recordMap.isEmpty()) {
            String[] masks = recordMap.split(",");
            for (String mask : masks) {
                String[] namePatternStreamMask = mask.split(":");
                String namePattern = namePatternStreamMask[0];
                String streamMask = namePatternStreamMask[1];
                /**
                 * Example
                 * record_map=.*1001.*:7,1002.*:3
                 *
                 * 7 is binary 111
                 * 1 - outgoing audio+video is ENABLED for recording
                 *      1 - incoming audio is ENABLED for recording
                 *          1 - incoming video is ENABLED for recording
                 *
                 * 3 is binary 011
                 *
                 * 0 - outgoing audio+video is DISABLED for recording
                 *      1 - incoming audio is ENABLED for recording
                 *          1 - incoming video is ENABLED for recording
                 */

                if (login.matches(namePattern)) {
                    bitMask = Byte.parseByte(streamMask);
                    break;
                }
            }
        }

        if (bitMask == 0) {
            log.info("BitMask was not found for login: " + login + " in recordMap: " + recordMap);
        }

        return bitMask;
    }

    protected void stopRecording(String callId) {
        String login = rtmpClient.getRtmpClientConfig().getAuthenticationName();
        log.info("stopRecording login: " + login);
        synchronized (recordingMap) {
            Iterator<IMediaStream> it = recordingMap.keySet().iterator();
            while (it.hasNext()) {
                IMediaStream stream = it.next();
                if (stream.getName().indexOf(callId) != -1) {
                    ILiveStreamRecord recorder = recordingMap.get(stream);
                    if (recorder != null) {
                        recorder.stopRecording();
                    }
                    it.remove();
                }
            }
        }
    }

}

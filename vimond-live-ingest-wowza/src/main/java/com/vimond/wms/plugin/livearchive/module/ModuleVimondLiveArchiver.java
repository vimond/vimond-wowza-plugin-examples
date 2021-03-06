package com.vimond.wms.plugin.livearchive.module;

import com.vimond.wms.plugin.livearchive.client.archive.Bucket;
import com.vimond.wms.plugin.livearchive.client.archive.ClipStatus;
import com.vimond.wms.plugin.livearchive.client.archive.VimondArchiveClient;
import com.vimond.wms.plugin.livearchive.client.auth0.Auth0Credentials;
import com.vimond.wms.plugin.livearchive.client.pushtarget.TargetClient;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.IMediaStreamActionNotify2;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * .
 *
 * @author Vimond Media Solution AS
 * @since 2018-03-06
 */
public class ModuleVimondLiveArchiver extends ModuleBase {

    private static final String VIMOND_TARGET_PREFIX = "s3-";

    private static final WMSLogger logger = WMSLoggerFactory.getLogger(null);
    Map<IMediaStream, StreamInitInfo> publishers = new HashMap<IMediaStream, StreamInitInfo>();

    private static VimondArchiveClient createVimondArchiveClient(VimondLiveArchiveModuleConfiguration config) {
        if(config.getIoDomain() == null) {
            return null;
        }
        Auth0Credentials auth0Credentials;
        try {
            auth0Credentials = new Auth0Credentials(
                config.getAuth0ClientId(),
                config.getAuth0ClientSecret(),
                config.getAuth0Audience(),
                Auth0Credentials.CLIENT_CREDENTIALS_GRANT_TYPE
            );
        } catch (Exception e) {
            logError("Auth0", "Auth setup failed", e.toString());
            throw new RuntimeException(e);
        }
        try {
            return new VimondArchiveClient(
                    config.getIoDomain(),
                    config.getIoClientId(),
                    config.getAuth0Domain(),
                    auth0Credentials
            );
        } catch (Exception e) {
            logError("VimondArchiveClient", "VimondArchiveClient setup failed", e.toString());
            throw new RuntimeException(e);
        }
    }

    private static void log(String streamName, String event, String action) {
        logger.warn("ModuleVimondLiveArchiver." + event + "." + action + " triggered by stream [" + streamName + "]");
    }

    private static void logError(String streamName, String event, String error) {
        logger.error("ModuleVimondLiveArchiver." + event + " triggered by stream [" + streamName + "]. Error: " + error);
    }

    public void stopPublisher(IMediaStream stream) {
        try {
            synchronized (publishers) {

                StreamInitInfo streamInitInfo = publishers.remove(stream);
                if (streamInitInfo == null) {
                    return;
                }

                IApplicationInstance appInstance = stream.getStreams().getAppInstance();

                // Load configuration from application properties
                log(stream.getName(), "onUnPublish", "Loading configuration");
                VimondLiveArchiveModuleConfiguration config = new VimondLiveArchiveModuleConfiguration(appInstance);

                // Delete push target towards S3
                log(stream.getName(), "onUnPublish", "Deleting S3 stream target");
                TargetClient targetClient = new TargetClient(
                        config.getWowzaPushTargetApiUsername(),
                        config.getWowzaPushTargetApiPassword()
                );
                targetClient.deleteStreamTarget(appInstance.getApplication().getName(), streamInitInfo.getName());


                // Archiving is activated, we will remove the stream and archive it as VOD
                log(stream.getName(), "onUnPublish", "Loading VimondArchiveClient");
                VimondArchiveClient vimondArchiveClient = createVimondArchiveClient(config);

                if (vimondArchiveClient != null) {
                    // Do not archive clips longer than 24 hours, those are considered to be linear
                    if (streamInitInfo.getStartTime().isAfter(Instant.now().minus(Duration.ofHours(24)))) {

                        log(stream.getName(), "onUnPublish", "Archiving stream in Vimond Live Archive");

                        Integer part = 1;
                        Boolean doneCliping = Boolean.FALSE;
                        Instant clipStartTime = streamInitInfo.getStartTime();

                        try {
                            while (!doneCliping) {

                                String clipName = streamInitInfo.getName() + ": Part " + part;
                                Optional<ClipStatus> clipStatus = vimondArchiveClient.createClip(
                                        streamInitInfo.getResource(),
                                        clipName,
                                        clipStartTime,
                                        config.getIoArchiveChunkDuration());

                                doneCliping = Boolean.TRUE;
                                if (clipStatus.isPresent()) {
                                    log(stream.getName(), "created archive clip", "[" + clipName + "]");

                                    // If the previous clips was not empty, we use the last fragment time as the start of the next chunk for accuracy for accuracy
                                    Optional<Instant> accurateEnd = clipStatus.get().getEnd();
                                    if (accurateEnd.isPresent()) {
                                        clipStartTime = accurateEnd.get();
                                        part += 1;
                                        doneCliping = Boolean.FALSE;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logError(stream.getName(), "error", e.toString());
                        }
                    }
                    // Unregister event from vimond live archive
                    log(stream.getName(), "onUnPublish", "Unregister stream from Vimond IO");
                    vimondArchiveClient.deleteEvent(streamInitInfo.getResource());
                }
            }
        } catch (Exception e) {
            logError(stream.getName(), "onUnPublish", e.toString());
        }
    }

    public void onStreamCreate(IMediaStream stream) {
        stream.addClientListener(new StreamNotify());
    }

    public void onStreamDestory(IMediaStream stream) {
        stopPublisher(stream);
    }

    class StreamInitInfo {

        private String name;
        private Instant startTime;
        private String resource;

        StreamInitInfo(String name, Instant startTime, String resource) {
            this.name = name;
            this.startTime = startTime;
            this.resource = resource;
        }

        public String getName() {
            return name;
        }

        public Instant getStartTime() {
            return startTime;
        }

        public String getResource() {
            return resource;
        }
    }

    class StreamNotify implements IMediaStreamActionNotify2 {

        public void onPlay(IMediaStream stream, String streamName, double playStart, double playLen, int playReset) {
        }

        public void onPause(IMediaStream stream, boolean isPause, double location) {
        }

        public void onSeek(IMediaStream stream, double location) {
        }

        public void onStop(IMediaStream stream) {
        }

        public void onMetaData(IMediaStream stream, AMFPacket metaDataPacket) {
        }

        public void onPauseRaw(IMediaStream stream, boolean isPause, double location) {
        }

        public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
            log(streamName, "onPublish", "init");
            if (!streamName.startsWith(VIMOND_TARGET_PREFIX)) // this is here to avoid looping pushes
            {

                try {
                    IApplicationInstance appInstance = stream.getStreams().getAppInstance();

                    synchronized (publishers) {

                        // Load configuration from application properties
                        log(streamName, "onPublish", "Loading VimondLiveArchiveModuleConfiguration");
                        VimondLiveArchiveModuleConfiguration config = new VimondLiveArchiveModuleConfiguration(appInstance);

                        Optional<String> eventResource = Optional.empty();

                        log(streamName, "onPublish", "Loading VimondArchiveClient");
                        VimondArchiveClient vimondArchiveClient = createVimondArchiveClient(config);
                        if (vimondArchiveClient != null) {
                            // Register new live archive ingest location towards Vimond Live Archive API
                            eventResource = vimondArchiveClient.createEvent(
                                    new Bucket(config.getS3IngestBucketName(), config.getS3Region()),
                                    new Bucket(config.getS3ArchiveBucketName(), config.getS3Region()),
                                    VIMOND_TARGET_PREFIX + streamName,
                                    streamName
                            );
                        }

                        log(streamName, "onPublish", "Activating new push target towards S3");

                        // Activate the new push target towards S3
                        TargetClient targetClient = new TargetClient(
                                config.getWowzaPushTargetApiUsername(),
                                config.getWowzaPushTargetApiPassword()
                        );
                        targetClient.createStreamTarget(
                                appInstance.getApplication().getName(),
                                streamName,
                                config.getS3IngestBucketName(),
                                config.getS3Region(),
                                config.getS3AccessKey(),
                                config.getS3SecretKey(),
                                config.getWowzaPushTargetWorkingDirectory(),
                                config.getWowzaPushTargetLocalHousekeeping()
                        );
                        log(streamName, "onPublish",
                                "Stream Target created: [" +
                                        " S3 IngestBucketName: " + config.getS3IngestBucketName() +
                                        ", S3 Access Key: " + config.getS3AccessKey() +
                                        ", S3 Secret Key: XXXXXXXXXX" + config.getS3SecretKey().substring(config.getS3SecretKey().length() - 4) +
                                        ", S3 Region: " + config.getS3Region() +
                                        "]");

                        // Store startup details
                        if(eventResource.isPresent()) {
                            StreamInitInfo initInfo = new StreamInitInfo(streamName, Instant.now(), eventResource.get());
                            publishers.put(stream, initInfo);
                        } else {
                            StreamInitInfo initInfo = new StreamInitInfo(streamName, Instant.now(), null);
                            publishers.put(stream, initInfo);
                        }
                    }

                } catch (Exception e) {
                    logError(streamName, "onPublish", e.toString());
                }
            }
        }

        public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
            log(streamName, "onUnPublish", "init");
            stopPublisher(stream);
        }
    }
}

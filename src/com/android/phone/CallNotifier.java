/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaDisplayInfoRec;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.gsm.SuppServiceNotification;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Phone app module that listens for phone state changes and various other
 * events from the telephony layer, and triggers any resulting UI behavior
 * (like starting the Incoming Call UI, playing in-call tones,
 * updating notifications, writing call log entries, etc.)
 */
public class CallNotifier extends Handler {
    private static final String LOG_TAG = "CallNotifier";
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // Time to display the message from the underlying phone layers.
    private static final int SHOW_MESSAGE_NOTIFICATION_TIME = 3000; // msec

    /** The singleton instance. */
    private static CallNotifier sInstance;

    private static final String ACTION_UICC_MANUAL_PROVISION_STATUS_CHANGED =
            "org.codeaurora.intent.action.ACTION_UICC_MANUAL_PROVISION_STATUS_CHANGED";
    private static final String EXTRA_NEW_PROVISION_STATE = "newProvisionState";
    private static final int NOT_PROVISIONED = 0;

    private Map<Integer, CallNotifierPhoneStateListener> mPhoneStateListeners =
            new ArrayMap<Integer, CallNotifierPhoneStateListener>();
    private Map<Integer, Boolean> mCFIStatus = new ArrayMap<Integer, Boolean>();
    private Map<Integer, Boolean> mMWIStatus = new ArrayMap<Integer, Boolean>();
    private PhoneGlobals mApplication;
    private CallManager mCM;
    private BluetoothHeadset mBluetoothHeadset;

    // ToneGenerator instance for playing SignalInfo tones
    private ToneGenerator mSignalInfoToneGenerator;

    // The tone volume relative to other sounds in the stream SignalInfo
    private static final int TONE_RELATIVE_VOLUME_SIGNALINFO = 80;

    private boolean mVoicePrivacyState = false;

    // Cached AudioManager
    private AudioManager mAudioManager;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;

    // Events from the Phone object:
    public static final int PHONE_DISCONNECT = 3;
    public static final int PHONE_STATE_DISPLAYINFO = 6;
    public static final int PHONE_STATE_SIGNALINFO = 7;
    public static final int PHONE_ENHANCED_VP_ON = 9;
    public static final int PHONE_ENHANCED_VP_OFF = 10;
    public static final int PHONE_SUPP_SERVICE_FAILED = 14;
    public static final int PHONE_TTY_MODE_RECEIVED = 15;
    public static final int PHONE_SUPP_SERVICE_NOTIFY = 16;
    // Events generated internally.
    // We should store all the possible event type values in one place to make sure that
    // they don't step on each others' toes.
    public static final int INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE = 22;
    // Other events from call manager
    public static final int EVENT_OTA_PROVISION_CHANGE = 20;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(LOG_TAG, "Intent received: " + action);
            if (ACTION_UICC_MANUAL_PROVISION_STATUS_CHANGED.equals(action)) {
                int slotId = intent.getIntExtra(PhoneConstants.PHONE_KEY,
                        SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                int newProvisionedState = intent.getIntExtra(EXTRA_NEW_PROVISION_STATE,
                        NOT_PROVISIONED);
                 Log.d(LOG_TAG, "Received ACTION_UICC_MANUAL_PROVISION_STATUS_CHANGED on slotId: "
                         + slotId + " new sub state " + newProvisionedState);
                int subId[] = SubscriptionController.getInstance().getSubIdUsingSlotId(slotId);
                if (mCFIStatus.containsKey(subId[0]) && (mCFIStatus.get(subId[0]) == true) &&
                    newProvisionedState == NOT_PROVISIONED) {
                    Log.d(LOG_TAG, "SubId: for updateCfi" +subId[0]+" "+"NOT_PROVISIONED");
                    mApplication.notificationMgr.updateCfi(subId[0], false);
                }
                if (mMWIStatus.containsKey(subId[0]) && (mMWIStatus.get(subId[0]) == true) &&
                    newProvisionedState == NOT_PROVISIONED) {
                    Log.d(LOG_TAG, "SubId: for updateMwi" +subId[0]+" "+"NOT_PROVISIONED");
                    mApplication.notificationMgr.updateMwi(subId[0], false);
                }

            }
        }
    };

    /**
     * Initialize the singleton CallNotifier instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static CallNotifier init(
            PhoneGlobals app) {
        synchronized (CallNotifier.class) {
            if (sInstance == null) {
                sInstance = new CallNotifier(app);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private CallNotifier(
            PhoneGlobals app) {
        mApplication = app;
        mCM = app.mCM;

        mAudioManager = (AudioManager) mApplication.getSystemService(Context.AUDIO_SERVICE);
        mTelephonyManager =
                (TelephonyManager) mApplication.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = (SubscriptionManager) mApplication.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        registerForNotifications();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(mApplication.getApplicationContext(),
                    mBluetoothProfileServiceListener,
                    BluetoothProfile.HEADSET);
        }

        mSubscriptionManager.addOnSubscriptionsChangedListener(
                new OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        updatePhoneStateListeners();
                    }
                });
        IntentFilter intentFilter = new IntentFilter(ACTION_UICC_MANUAL_PROVISION_STATUS_CHANGED);
        mApplication.getApplicationContext().registerReceiver(mReceiver, intentFilter);
    }

    private void createSignalInfoToneGenerator() {
        // Instantiate the ToneGenerator for SignalInfo and CallWaiting
        // TODO: We probably don't need the mSignalInfoToneGenerator instance
        // around forever. Need to change it so as to create a ToneGenerator instance only
        // when a tone is being played and releases it after its done playing.
        if (mSignalInfoToneGenerator == null) {
            try {
                mSignalInfoToneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL,
                        TONE_RELATIVE_VOLUME_SIGNALINFO);
                Log.d(LOG_TAG, "CallNotifier: mSignalInfoToneGenerator created when toneplay");
            } catch (RuntimeException e) {
                Log.w(LOG_TAG, "CallNotifier: Exception caught while creating " +
                        "mSignalInfoToneGenerator: " + e);
                mSignalInfoToneGenerator = null;
            }
        } else {
            Log.d(LOG_TAG, "mSignalInfoToneGenerator created already, hence skipping");
        }
    }

    /**
     * Register for call state notifications with the CallManager.
     */
    private void registerForNotifications() {
        mCM.registerForDisconnect(this, PHONE_DISCONNECT, null);
        mCM.registerForCdmaOtaStatusChange(this, EVENT_OTA_PROVISION_CHANGE, null);
        mCM.registerForDisplayInfo(this, PHONE_STATE_DISPLAYINFO, null);
        mCM.registerForSignalInfo(this, PHONE_STATE_SIGNALINFO, null);
        mCM.registerForInCallVoicePrivacyOn(this, PHONE_ENHANCED_VP_ON, null);
        mCM.registerForInCallVoicePrivacyOff(this, PHONE_ENHANCED_VP_OFF, null);
        mCM.registerForSuppServiceFailed(this, PHONE_SUPP_SERVICE_FAILED, null);
        mCM.registerForTtyModeReceived(this, PHONE_TTY_MODE_RECEIVED, null);
        mCM.registerForSuppServiceNotification(this, PHONE_SUPP_SERVICE_NOTIFY, null);
    }

    @Override
    public void handleMessage(Message msg) {
        if (DBG) {
            Log.d(LOG_TAG, "handleMessage(" + msg.what + ")");
        }
        switch (msg.what) {
            case PHONE_DISCONNECT:
                if (DBG) log("DISCONNECT");
                // Stop any signalInfo tone being played when a call gets ended, the rest of the
                // disconnect functionality in onDisconnect() is handled in ConnectionService.
                stopSignalInfoTone();
                break;

            case PHONE_STATE_DISPLAYINFO:
                if (DBG) log("Received PHONE_STATE_DISPLAYINFO event");
                onDisplayInfo((AsyncResult) msg.obj);
                break;

            case PHONE_STATE_SIGNALINFO:
                if (DBG) log("Received PHONE_STATE_SIGNALINFO event");
                onSignalInfo((AsyncResult) msg.obj);
                break;

            case INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE:
                if (DBG) log("Received Display Info notification done event ...");
                PhoneDisplayMessage.dismissMessage();
                break;

            case EVENT_OTA_PROVISION_CHANGE:
                if (DBG) log("EVENT_OTA_PROVISION_CHANGE...");
                mApplication.handleOtaspEvent(msg);
                break;

            case PHONE_ENHANCED_VP_ON:
                if (DBG) log("PHONE_ENHANCED_VP_ON...");
                if (!mVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mVoicePrivacyState = true;
                }
                break;

            case PHONE_ENHANCED_VP_OFF:
                if (DBG) log("PHONE_ENHANCED_VP_OFF...");
                if (mVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mVoicePrivacyState = false;
                }
                break;

            case PHONE_SUPP_SERVICE_FAILED:
                if (DBG) log("PHONE_SUPP_SERVICE_FAILED...");
                onSuppServiceFailed((AsyncResult) msg.obj);
                break;

            case PHONE_TTY_MODE_RECEIVED:
                if (DBG) log("Received PHONE_TTY_MODE_RECEIVED event");
                onTtyModeReceived((AsyncResult) msg.obj);
                break;

            case PHONE_SUPP_SERVICE_NOTIFY:
                if (DBG) log("Received Supplementary Notification");
                onSuppServiceNotification((AsyncResult) msg.obj);
                break;

            default:
                // super.handleMessage(msg);
        }
    }

    void updateCallNotifierRegistrationsAfterRadioTechnologyChange() {
        if (DBG) Log.d(LOG_TAG, "updateCallNotifierRegistrationsAfterRadioTechnologyChange...");

        // Instantiate mSignalInfoToneGenerator
        createSignalInfoToneGenerator();
    }

    private void onSuppServiceNotification(AsyncResult r) {
        SuppServiceNotification notification = (SuppServiceNotification) r.result;

        /* show a toast for transient notifications */
        int toastResId = getSuppServiceToastTextResIdIfEnabled(notification);
        if (toastResId >= 0) {
            Toast.makeText(mApplication, mApplication.getString(toastResId),
                    Toast.LENGTH_LONG).show();
        }
    }

    protected int getSuppServiceToastTextResIdIfEnabled(SuppServiceNotification notification) {
        if (!PhoneSettings.showInCallEvents(mApplication)) {
            /* don't show anything if the user doesn't want it */
            return -1;
        }
        return getSuppServiceToastTextResId(notification);
    }

    protected int getSuppServiceToastTextResId(SuppServiceNotification notification) {
        if (notification.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MO) {
            switch (notification.code) {
                case SuppServiceNotification.MO_CODE_UNCONDITIONAL_CF_ACTIVE :
                    // This message is displayed when an outgoing call is made
                    // and unconditional forwarding is enabled.
                    return R.string.call_notif_unconditionalCF;
                case SuppServiceNotification.MO_CODE_SOME_CF_ACTIVE:
                    // This message is displayed when an outgoing call is made
                    // and conditional forwarding is enabled.
                    return R.string.call_notif_conditionalCF;
                case SuppServiceNotification.MO_CODE_CALL_FORWARDED:
                    //This message is displayed on A when the outgoing call actually gets forwarded to C
                    return R.string.call_notif_MOcall_forwarding;
                case SuppServiceNotification.MO_CODE_CUG_CALL:
                    //This message is displayed on A, when A makes call to B, both A & B
                    //belong to a CUG group
                    return R.string.call_notif_cugcall;
                case SuppServiceNotification.MO_CODE_OUTGOING_CALLS_BARRED:
                    //This message is displayed on A when outging is barred on A
                    return R.string.call_notif_outgoing_barred;
                case SuppServiceNotification.MO_CODE_INCOMING_CALLS_BARRED:
                    //This message is displayed on A, when A is calling B & incoming is barred on B
                    return R.string.call_notif_incoming_barred;
                case SuppServiceNotification.MO_CODE_CLIR_SUPPRESSION_REJECTED:
                    //This message is displayed on A, when CLIR suppression is rejected
                    return R.string.call_notif_clir_suppression_rejected;
                case SuppServiceNotification.MO_CODE_CALL_DEFLECTED:
                    //This message is displayed on A, when the outgoing call gets deflected to C from B
                    return R.string.call_notif_call_deflected;
            }
        } else if (notification.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MT) {
            switch (notification.code) {
                case SuppServiceNotification.MT_CODE_CUG_CALL:
                    //This message is displayed on B, when A makes call to B, both A & B
                    //belong to a CUG group
                    return R.string.call_notif_cugcall;
               case SuppServiceNotification.MT_CODE_MULTI_PARTY_CALL:
                   //This message is displayed on B when the the call is changed as multiparty
                   return R.string.call_notif_multipartycall;
               case SuppServiceNotification.MT_CODE_ON_HOLD_CALL_RELEASED:
                   //This message is displayed on B, when A makes call to B, puts it on hold & then releases it.
                   return R.string.call_notif_callonhold_released;
               case SuppServiceNotification.MT_CODE_FORWARD_CHECK_RECEIVED:
                   //This message is displayed on C when the incoming call is forwarded from B
                   return R.string.call_notif_forwardcheckreceived;
               case SuppServiceNotification.MT_CODE_CALL_CONNECTING_ECT:
                   //This message is displayed on B,when Call is connecting through Explicit Call Transfer
                   return R.string.call_notif_callconnectingect;
               case SuppServiceNotification.MT_CODE_CALL_CONNECTED_ECT:
                   //This message is displayed on B,when Call is connected through Explicit Call Transfer
                   return R.string.call_notif_callconnectedect;
               case SuppServiceNotification.MT_CODE_ADDITIONAL_CALL_FORWARDED:
                   // This message is displayed on B when it is busy and the incoming call gets forwarded to C
                   return R.string.call_notif_MTcall_forwarding;
            }
        }

        return -1;
    }

    /**
     * Resets the audio mode and speaker state when a call ends.
     */
    private void resetAudioStateAfterDisconnect() {
        if (VDBG) log("resetAudioStateAfterDisconnect()...");

        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.disconnectAudio();
        }

        // call turnOnSpeaker() with state=false and store=true even if speaker
        // is already off to reset user requested speaker state.
        PhoneUtils.turnOnSpeaker(mApplication, false, true);

        PhoneUtils.setAudioMode(mCM);
    }

    /**
     * Helper class to play tones through the earpiece (or speaker / BT)
     * during a call, using the ToneGenerator.
     *
     * To use, just instantiate a new InCallTonePlayer
     * (passing in the TONE_* constant for the tone you want)
     * and start() it.
     *
     * When we're done playing the tone, if the phone is idle at that
     * point, we'll reset the audio routing and speaker state.
     * (That means that for tones that get played *after* a call
     * disconnects, like "busy" or "congestion" or "call ended", you
     * should NOT call resetAudioStateAfterDisconnect() yourself.
     * Instead, just start the InCallTonePlayer, which will automatically
     * defer the resetAudioStateAfterDisconnect() call until the tone
     * finishes playing.)
     */
    private class InCallTonePlayer extends Thread {
        private int mToneId;
        private int mState;
        // The possible tones we can play.
        public static final int TONE_NONE = 0;
        public static final int TONE_CALL_WAITING = 1;
        public static final int TONE_BUSY = 2;
        public static final int TONE_CONGESTION = 3;
        public static final int TONE_CALL_ENDED = 4;
        public static final int TONE_VOICE_PRIVACY = 5;
        public static final int TONE_REORDER = 6;
        public static final int TONE_INTERCEPT = 7;
        public static final int TONE_CDMA_DROP = 8;
        public static final int TONE_OUT_OF_SERVICE = 9;
        public static final int TONE_REDIAL = 10;
        public static final int TONE_OTA_CALL_END = 11;
        public static final int TONE_UNOBTAINABLE_NUMBER = 13;

        // The tone volume relative to other sounds in the stream
        static final int TONE_RELATIVE_VOLUME_EMERGENCY = 100;
        static final int TONE_RELATIVE_VOLUME_HIPRI = 80;
        static final int TONE_RELATIVE_VOLUME_LOPRI = 50;

        // Buffer time (in msec) to add on to tone timeout value.
        // Needed mainly when the timeout value for a tone is the
        // exact duration of the tone itself.
        static final int TONE_TIMEOUT_BUFFER = 20;

        // The tone state
        static final int TONE_OFF = 0;
        static final int TONE_ON = 1;
        static final int TONE_STOPPED = 2;

        InCallTonePlayer(int toneId) {
            super();
            mToneId = toneId;
            mState = TONE_OFF;
        }

        @Override
        public void run() {
            log("InCallTonePlayer.run(toneId = " + mToneId + ")...");

            int toneType = 0;  // passed to ToneGenerator.startTone()
            int toneVolume;  // passed to the ToneGenerator constructor
            int toneLengthMillis;
            int phoneType = mCM.getFgPhone().getPhoneType();

            switch (mToneId) {
                case TONE_CALL_WAITING:
                    toneType = ToneGenerator.TONE_SUP_CALL_WAITING;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    // Call waiting tone is stopped by stopTone() method
                    toneLengthMillis = Integer.MAX_VALUE - TONE_TIMEOUT_BUFFER;
                    break;
                case TONE_BUSY:
                    if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                        toneType = ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT;
                        toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                        toneLengthMillis = 1000;
                    } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM
                            || phoneType == PhoneConstants.PHONE_TYPE_SIP
                            || phoneType == PhoneConstants.PHONE_TYPE_IMS
                            || phoneType == PhoneConstants.PHONE_TYPE_THIRD_PARTY) {
                        toneType = ToneGenerator.TONE_SUP_BUSY;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 4000;
                    } else {
                        throw new IllegalStateException("Unexpected phone type: " + phoneType);
                    }
                    break;
                case TONE_CONGESTION:
                    toneType = ToneGenerator.TONE_SUP_CONGESTION;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 4000;
                    break;

                case TONE_CALL_ENDED:
                    toneType = ToneGenerator.TONE_PROP_PROMPT;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 200;
                    break;
                 case TONE_OTA_CALL_END:
                    if (mApplication.cdmaOtaConfigData.otaPlaySuccessFailureTone ==
                            OtaUtils.OTA_PLAY_SUCCESS_FAILURE_TONE_ON) {
                        toneType = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 750;
                    } else {
                        toneType = ToneGenerator.TONE_PROP_PROMPT;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 200;
                    }
                    break;
                case TONE_VOICE_PRIVACY:
                    toneType = ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 5000;
                    break;
                case TONE_REORDER:
                    toneType = ToneGenerator.TONE_CDMA_REORDER;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 4000;
                    break;
                case TONE_INTERCEPT:
                    toneType = ToneGenerator.TONE_CDMA_ABBR_INTERCEPT;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 500;
                    break;
                case TONE_CDMA_DROP:
                case TONE_OUT_OF_SERVICE:
                    toneType = ToneGenerator.TONE_CDMA_CALLDROP_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 375;
                    break;
                case TONE_REDIAL:
                    toneType = ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 5000;
                    break;
                case TONE_UNOBTAINABLE_NUMBER:
                    toneType = ToneGenerator.TONE_SUP_ERROR;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 4000;
                    break;
                default:
                    throw new IllegalArgumentException("Bad toneId: " + mToneId);
            }

            // If the mToneGenerator creation fails, just continue without it.  It is
            // a local audio signal, and is not as important.
            ToneGenerator toneGenerator;
            try {
                int stream;
                if (mBluetoothHeadset != null) {
                    stream = mBluetoothHeadset.isAudioOn() ? AudioManager.STREAM_BLUETOOTH_SCO:
                        AudioManager.STREAM_VOICE_CALL;
                } else {
                    stream = AudioManager.STREAM_VOICE_CALL;
                }
                toneGenerator = new ToneGenerator(stream, toneVolume);
                // if (DBG) log("- created toneGenerator: " + toneGenerator);
            } catch (RuntimeException e) {
                Log.w(LOG_TAG,
                      "InCallTonePlayer: Exception caught while creating ToneGenerator: " + e);
                toneGenerator = null;
            }

            // Using the ToneGenerator (with the CALL_WAITING / BUSY /
            // CONGESTION tones at least), the ToneGenerator itself knows
            // the right pattern of tones to play; we do NOT need to
            // manually start/stop each individual tone, or manually
            // insert the correct delay between tones.  (We just start it
            // and let it run for however long we want the tone pattern to
            // continue.)
            //
            // TODO: When we stop the ToneGenerator in the middle of a
            // "tone pattern", it sounds bad if we cut if off while the
            // tone is actually playing.  Consider adding API to the
            // ToneGenerator to say "stop at the next silent part of the
            // pattern", or simply "play the pattern N times and then
            // stop."
            boolean needToStopTone = true;
            boolean okToPlayTone = false;

            if (toneGenerator != null) {
                int ringerMode = mAudioManager.getRingerMode();
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    if (toneType == ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("- InCallTonePlayer: start playing call tone=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT) ||
                            (toneType == ToneGenerator.TONE_CDMA_REORDER) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_REORDER) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_INTERCEPT) ||
                            (toneType == ToneGenerator.TONE_CDMA_CALLDROP_LITE)) {
                        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                            if (DBG) log("InCallTonePlayer:playing call fail tone:" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE) ||
                               (toneType == ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE)) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("InCallTonePlayer:playing tone for toneType=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else { // For the rest of the tones, always OK to play.
                        okToPlayTone = true;
                    }
                } else {  // Not "CDMA"
                    okToPlayTone = true;
                }

                synchronized (this) {
                    if (okToPlayTone && mState != TONE_STOPPED) {
                        mState = TONE_ON;
                        toneGenerator.startTone(toneType);
                        try {
                            wait(toneLengthMillis + TONE_TIMEOUT_BUFFER);
                        } catch  (InterruptedException e) {
                            Log.w(LOG_TAG,
                                  "InCallTonePlayer stopped: " + e);
                        }
                        if (needToStopTone) {
                            toneGenerator.stopTone();
                        }
                    }
                    // if (DBG) log("- InCallTonePlayer: done playing.");
                    toneGenerator.release();
                    mState = TONE_OFF;
                }
            }

            // Finally, do the same cleanup we otherwise would have done
            // in onDisconnect().
            //
            // (But watch out: do NOT do this if the phone is in use,
            // since some of our tones get played *during* a call (like
            // CALL_WAITING) and we definitely *don't*
            // want to reset the audio mode / speaker / bluetooth after
            // playing those!
            // This call is really here for use with tones that get played
            // *after* a call disconnects, like "busy" or "congestion" or
            // "call ended", where the phone has already become idle but
            // we need to defer the resetAudioStateAfterDisconnect() call
            // till the tone finishes playing.)
            if (mCM.getState() == PhoneConstants.State.IDLE) {
                resetAudioStateAfterDisconnect();
            }
        }
    }

    /**
     * Displays a notification when the phone receives a DisplayInfo record.
     */
    private void onDisplayInfo(AsyncResult r) {
        // Extract the DisplayInfo String from the message
        CdmaDisplayInfoRec displayInfoRec = (CdmaDisplayInfoRec)(r.result);

        if (displayInfoRec != null) {
            String displayInfo = displayInfoRec.alpha;
            if (DBG) log("onDisplayInfo: displayInfo=" + displayInfo);
            PhoneDisplayMessage.displayNetworkMessage(mApplication, displayInfo);

            // start a timer that kills the dialog
            sendEmptyMessageDelayed(INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE,
                    SHOW_MESSAGE_NOTIFICATION_TIME);
        }
    }

    /**
     * Displays a notification when the phone receives a notice that a supplemental
     * service has failed.
     */
    private void onSuppServiceFailed(AsyncResult r) {
        Phone.SuppService service = (Phone.SuppService) r.result;
        Log.d(LOG_TAG, "onSuppServiceFailed: " + service);
        String errorMessageString;
        switch (service) {
            case SWITCH:
            case RESUME:
                // Attempt to switch foreground and background/incoming calls failed
                // ("Failed to switch calls")
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_switch);
                break;

            case SEPARATE:
                // Attempt to separate a call from a conference call
                // failed ("Failed to separate out call")
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_separate);
                break;

            case TRANSFER:
                // Attempt to connect foreground and background calls to
                // each other (and hanging up user's line) failed ("Call
                // transfer failed")
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_transfer);
                break;

            case CONFERENCE:
                // Attempt to add a call to conference call failed
                // ("Conference call failed")
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_conference);
                break;

            case REJECT:
                // Attempt to reject an incoming call failed
                // ("Call rejection failed")
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_reject);
                break;

            case HANGUP:
                // Attempt to release a call failed ("Failed to release call(s)")
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_hangup);
                break;

            case HOLD:
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_hold);
                break;

            case UNKNOWN:
            default:
                // Attempt to use a service we don't recognize or support
                // ("Unsupported service" or "Selected service failed")
                errorMessageString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_unknown);
                break;
        }
        PhoneDisplayMessage.displayErrorMessage(mApplication, errorMessageString);

        // start a timer that kills the dialog
        sendEmptyMessageDelayed(INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE,
                SHOW_MESSAGE_NOTIFICATION_TIME);
    }

    public void updatePhoneStateListeners() {
        List<SubscriptionInfo> subInfos = mSubscriptionManager.getActiveSubscriptionInfoList();

        // Unregister phone listeners for inactive subscriptions.
        Iterator<Integer> itr = mPhoneStateListeners.keySet().iterator();
        while (itr.hasNext()) {
            int subId = itr.next();
            if (subInfos == null || !containsSubId(subInfos, subId)) {
                Log.d(LOG_TAG, "updatePhoneStateListeners: Hide the outstanding notifications.");
                // Hide the outstanding notifications.
                mApplication.notificationMgr.updateMwi(subId, false);
                mApplication.notificationMgr.updateCfi(subId, false);

                // Listening to LISTEN_NONE removes the listener.
                mTelephonyManager.listen(
                        mPhoneStateListeners.get(subId), PhoneStateListener.LISTEN_NONE);
                itr.remove();
            } else {
                Log.d(LOG_TAG, "updatePhoneStateListeners: update CF notifications.");

                if (mCFIStatus.containsKey(subId)) {
                    mApplication.notificationMgr.updateCfi(subId, mCFIStatus.get(subId));
                }
                if (mMWIStatus.containsKey(subId)) {
                    mApplication.notificationMgr.updateMwi(subId, mMWIStatus.get(subId));
                }
            }
        }

        if (subInfos == null) {
            return;
        }

        // Register new phone listeners for active subscriptions.
        for (int i = 0; i < subInfos.size(); i++) {
            int subId = subInfos.get(i).getSubscriptionId();
            if (!mPhoneStateListeners.containsKey(subId)) {
                CallNotifierPhoneStateListener listener = new CallNotifierPhoneStateListener(subId);
                mTelephonyManager.listen(listener,
                        PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR
                        | PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR);
                mPhoneStateListeners.put(subId, listener);
            }
        }
    }

    /**
     * @return {@code true} if the list contains SubscriptionInfo with the given subscription id.
     */
    private boolean containsSubId(List<SubscriptionInfo> subInfos, int subId) {
        if (subInfos == null) {
            return false;
        }

        for (int i = 0; i < subInfos.size(); i++) {
            if (subInfos.get(i).getSubscriptionId() == subId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Displays a notification when the phone receives a notice that TTY mode
     * has changed on remote end.
     */
    private void onTtyModeReceived(AsyncResult r) {
        if (DBG) log("TtyModeReceived: displaying notification message");

        int resId = 0;
        switch (((Integer)r.result).intValue()) {
            case TelecomManager.TTY_MODE_FULL:
                resId = com.android.internal.R.string.peerTtyModeFull;
                break;
            case TelecomManager.TTY_MODE_HCO:
                resId = com.android.internal.R.string.peerTtyModeHco;
                break;
            case TelecomManager.TTY_MODE_VCO:
                resId = com.android.internal.R.string.peerTtyModeVco;
                break;
            case TelecomManager.TTY_MODE_OFF:
                resId = com.android.internal.R.string.peerTtyModeOff;
                break;
            default:
                Log.e(LOG_TAG, "Unsupported TTY mode: " + r.result);
                break;
        }
        if (resId != 0) {
            PhoneDisplayMessage.displayNetworkMessage(mApplication,
                    mApplication.getResources().getString(resId));

            // start a timer that kills the dialog
            sendEmptyMessageDelayed(INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE,
                    SHOW_MESSAGE_NOTIFICATION_TIME);
        }
    }

    /**
     * Helper class to play SignalInfo tones using the ToneGenerator.
     *
     * To use, just instantiate a new SignalInfoTonePlayer
     * (passing in the ToneID constant for the tone you want)
     * and start() it.
     */
    private class SignalInfoTonePlayer extends Thread {
        private int mToneId;

        SignalInfoTonePlayer(int toneId) {
            super();
            mToneId = toneId;
        }

        @Override
        public void run() {
            log("SignalInfoTonePlayer.run(toneId = " + mToneId + ")...");
            createSignalInfoToneGenerator();
            if (mSignalInfoToneGenerator != null) {
                //First stop any ongoing SignalInfo tone
                mSignalInfoToneGenerator.stopTone();

                //Start playing the new tone if its a valid tone
                mSignalInfoToneGenerator.startTone(mToneId);
            }
        }
    }

    /**
     * Plays a tone when the phone receives a SignalInfo record.
     */
    private void onSignalInfo(AsyncResult r) {
        // Signal Info are totally ignored on non-voice-capable devices.
        if (!PhoneGlobals.sVoiceCapable) {
            Log.w(LOG_TAG, "Got onSignalInfo() on non-voice-capable device! Ignoring...");
            return;
        }

        if (PhoneUtils.isRealIncomingCall(mCM.getFirstActiveRingingCall().getState())) {
            // Do not start any new SignalInfo tone when Call state is INCOMING
            // and stop any previous SignalInfo tone which is being played
            stopSignalInfoTone();
        } else {
            // Extract the SignalInfo String from the message
            CdmaSignalInfoRec signalInfoRec = (CdmaSignalInfoRec)(r.result);
            // Only proceed if a Signal info is present.
            if (signalInfoRec != null) {
                boolean isPresent = signalInfoRec.isPresent;
                if (DBG) log("onSignalInfo: isPresent=" + isPresent);
                if (isPresent) {// if tone is valid
                    int uSignalType = signalInfoRec.signalType;
                    int uAlertPitch = signalInfoRec.alertPitch;
                    int uSignal = signalInfoRec.signal;

                    if (DBG) log("onSignalInfo: uSignalType=" + uSignalType + ", uAlertPitch=" +
                            uAlertPitch + ", uSignal=" + uSignal);
                    //Map the Signal to a ToneGenerator ToneID only if Signal info is present
                    int toneID = SignalToneUtil.getAudioToneFromSignalInfo
                            (uSignalType, uAlertPitch, uSignal);

                    //Create the SignalInfo tone player and pass the ToneID
                    new SignalInfoTonePlayer(toneID).start();
                }
            }
        }
    }

    /**
     * Stops a SignalInfo tone in the following condition
     * 1 - On receiving a New Ringing Call
     * 2 - On disconnecting a call
     * 3 - On answering a Call Waiting Call
     */
    /* package */ void stopSignalInfoTone() {
        if (DBG) log("stopSignalInfoTone: Stopping SignalInfo tone player");
        new SignalInfoTonePlayer(ToneGenerator.TONE_CDMA_SIGNAL_OFF).start();
    }

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
           new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mBluetoothHeadset = (BluetoothHeadset) proxy;
                    if (VDBG) log("- Got BluetoothHeadset: " + mBluetoothHeadset);
                }

                public void onServiceDisconnected(int profile) {
                    mBluetoothHeadset = null;
                }
            };

    private class CallNotifierPhoneStateListener extends PhoneStateListener {
        public CallNotifierPhoneStateListener(int subId) {
            super(subId);
        }

        @Override
        public void onMessageWaitingIndicatorChanged(boolean visible) {
            if (VDBG) log("onMessageWaitingIndicatorChanged(): " + this.mSubId + " " + visible);
            mMWIStatus.put(this.mSubId, visible);
            mApplication.notificationMgr.updateMwi(this.mSubId, visible);
        }

        @Override
        public void onCallForwardingIndicatorChanged(boolean visible) {
            if (VDBG) log("onCallForwardingIndicatorChanged(): " + this.mSubId + " " + visible);
            mCFIStatus.put(this.mSubId, visible);
            mApplication.notificationMgr.updateCfi(this.mSubId, visible);
        }
    };

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
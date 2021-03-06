package org.tvheadend.tvhclient.data.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import org.json.JSONException
import org.json.JSONObject
import org.tvheadend.tvhclient.MainApplication
import org.tvheadend.tvhclient.R
import org.tvheadend.tvhclient.data.repository.AppRepository
import org.tvheadend.tvhclient.data.service.htsp.*
import org.tvheadend.tvhclient.data.worker.EpgDataUpdateWorker
import org.tvheadend.tvhclient.domain.entity.*
import org.tvheadend.tvhclient.ui.common.sendSnackbarMessage
import org.tvheadend.tvhclient.ui.features.notification.addNotification
import org.tvheadend.tvhclient.ui.features.notification.removeNotificationById
import org.tvheadend.tvhclient.util.convertUrlToHashString
import org.tvheadend.tvhclient.util.getIconUrl
import timber.log.Timber
import java.io.*
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class HtspService : Service(), HtspConnectionStateListener, HtspMessageListener {

    @Inject
    lateinit var appContext: Context
    @Inject
    lateinit var appRepository: AppRepository
    @Inject
    lateinit var sharedPreferences: SharedPreferences

    private lateinit var execService: ScheduledExecutorService
    private lateinit var connection: Connection
    private var htspConnection: HtspConnection? = null

    private val pendingEventOps = ArrayList<Program>()
    private val pendingChannelOps = ArrayList<Channel>()
    private val pendingChannelTagOps = ArrayList<ChannelTag>()
    private val pendingRecordingOps = ArrayList<Recording>()

    private var initialSyncWithServerRunning: Boolean = false
    private var syncEventsRequired: Boolean = false
    private var syncRequired: Boolean = false
    private var firstEventReceived = false
    private var htspVersion = 13
    private var serverStatus: ServerStatus? = null
    private var connectionTimeout: Int = 0

    override fun onCreate() {
        Timber.d("Starting service")
        MainApplication.getComponent().inject(this)

        execService = Executors.newScheduledThreadPool(10)
        serverStatus = appRepository.serverStatusData.activeItem
        htspVersion = serverStatus?.htspVersion ?: 13
        connectionTimeout = Integer.valueOf(sharedPreferences.getString("connection_timeout", appContext.resources.getString(R.string.pref_default_connection_timeout))!!) * 1000
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.action
        if (action == null || action.isEmpty()) {
            return Service.START_NOT_STICKY
        }
        Timber.d("Received command $action for service")

        when (action) {
            "connect" -> {
                Timber.d("Connection to server requested")
                startHtspConnection()
            }
            "reconnect" -> {
                Timber.d("Reconnection to server requested")
                htspConnection?.let {
                    if (it.isNotConnected) {
                        Timber.d("Reconnecting to server because we are not connected anymore")
                        startHtspConnection()
                    } else {
                        Timber.d("Not reconnecting to server because we are still connected")
                    }
                } ?: run {
                    Timber.d("Reconnecting to server because no previous connection existed")
                    startHtspConnection()
                }
            }
            "getDiskSpace" -> getDiscSpace()
            "getSysTime" -> getSystemTime()
            "getChannel" -> getChannel(intent)
            "getEvent" -> getEvent(intent)
            "getEvents" -> getEvents(intent)
            "epgQuery" -> getEpgQuery(intent)
            "addDvrEntry" -> addDvrEntry(intent)
            "updateDvrEntry" -> updateDvrEntry(intent)
            "cancelDvrEntry", "deleteDvrEntry", "stopDvrEntry" -> removeDvrEntry(intent)
            "addAutorecEntry" -> addAutorecEntry(intent)
            "updateAutorecEntry" -> updateAutorecEntry(intent)
            "deleteAutorecEntry" -> deleteAutorecEntry(intent)
            "addTimerecEntry" -> addTimerrecEntry(intent)
            "updateTimerecEntry" -> updateTimerrecEntry(intent)
            "deleteTimerecEntry" -> deleteTimerrecEntry(intent)
            "getTicket" -> getTicket(intent)
            "getProfiles" -> getProfiles()
            "getDvrConfigs" -> getDvrConfigs()
            // Internal calls that are called from the intent service
            "getMoreEvents" -> getMoreEvents(intent)
            "loadChannelIcons" -> loadAllChannelIcons()
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        Timber.d("Stopping service")
        execService.shutdown()
        stopHtspConnection()
    }

    private fun startHtspConnection() {
        Timber.d("Starting connection")
        stopHtspConnection()

        connection = appRepository.connectionData.activeItem

        htspConnection = HtspConnection(
                connection.username, connection.password,
                connection.hostname, connection.port,
                connectionTimeout,
                this, this)
        // Since this is blocking, spawn to a new thread
        execService.execute {
            htspConnection?.openConnection()
            htspConnection?.authenticate()
        }
    }

    private fun stopHtspConnection() {
        Timber.d("Stopping connection")
        htspConnection?.closeConnection()
    }

    override fun onMessage(response: HtspMessage) {
        val method = response.method
        when (method) {
            "tagAdd" -> onTagAdd(response)
            "tagUpdate" -> onTagUpdate(response)
            "tagDelete" -> onTagDelete(response)
            "channelAdd" -> onChannelAdd(response)
            "channelUpdate" -> onChannelUpdate(response)
            "channelDelete" -> onChannelDelete(response)
            "dvrEntryAdd" -> onDvrEntryAdd(response)
            "dvrEntryUpdate" -> onDvrEntryUpdate(response)
            "dvrEntryDelete" -> onDvrEntryDelete(response)
            "timerecEntryAdd" -> onTimerRecEntryAdd(response)
            "timerecEntryUpdate" -> onTimerRecEntryUpdate(response)
            "timerecEntryDelete" -> onTimerRecEntryDelete(response)
            "autorecEntryAdd" -> onAutorecEntryAdd(response)
            "autorecEntryUpdate" -> onAutorecEntryUpdate(response)
            "autorecEntryDelete" -> onAutorecEntryDelete(response)
            "eventAdd" -> onEventAdd(response)
            "eventUpdate" -> onEventUpdate(response)
            "eventDelete" -> onEventDelete(response)
            "initialSyncCompleted" -> onInitialSyncCompleted()
            "getSysTime" -> onSystemTime(response)
            "getDiskSpace" -> onDiskSpace(response)
            "getProfiles" -> onHtspProfiles(response)
            "getDvrConfigs" -> onDvrConfigs(response)
            "getEvents" -> onGetEvents(response, Intent())
            "serverStatus" -> onServerStatus(response)
            else -> {
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onAuthenticationStateChange(state: HtspConnection.AuthenticationState) {
        Timber.d("Authentication state changed to $state")

        when (state) {
            HtspConnection.AuthenticationState.FAILED ->
                sendSyncStateMessage(SyncStateReceiver.State.FAILED,
                        getString(R.string.authentication_failed), "")

            HtspConnection.AuthenticationState.FAILED_BAD_CREDENTIALS ->
                sendSyncStateMessage(SyncStateReceiver.State.FAILED,
                        getString(R.string.authentication_failed),
                        getString(R.string.bad_username_or_password))

            HtspConnection.AuthenticationState.AUTHENTICATED -> {
                sendSyncStateMessage(SyncStateReceiver.State.CONNECTED,
                        getString(R.string.connected_to_server), "")
                startAsyncCommunicationWithServer()
            }
            else -> {
            }
        }
    }

    override fun onConnectionStateChange(state: HtspConnection.ConnectionState) {
        Timber.d("Simple HTSP connection state changed, state is $state")

        when (state) {
            HtspConnection.ConnectionState.FAILED ->
                sendSyncStateMessage(SyncStateReceiver.State.FAILED,
                        getString(R.string.connection_failed), "")

            HtspConnection.ConnectionState.FAILED_CONNECTING_TO_SERVER ->
                sendSyncStateMessage(SyncStateReceiver.State.FAILED,
                        getString(R.string.connection_failed),
                        getString(R.string.failed_connecting_to_server))

            HtspConnection.ConnectionState.FAILED_EXCEPTION_OPENING_SOCKET ->
                sendSyncStateMessage(SyncStateReceiver.State.FAILED,
                        getString(R.string.connection_failed),
                        getString(R.string.failed_opening_socket))

            HtspConnection.ConnectionState.FAILED_INTERRUPTED ->
                sendSyncStateMessage(SyncStateReceiver.State.FAILED,
                        getString(R.string.connection_failed),
                        getString(R.string.failed_during_connection_attempt))

            HtspConnection.ConnectionState.FAILED_UNRESOLVED_ADDRESS ->
                sendSyncStateMessage(SyncStateReceiver.State.FAILED,
                        getString(R.string.connection_failed),
                        getString(R.string.failed_to_resolve_address))

            HtspConnection.ConnectionState.CONNECTING ->
                sendSyncStateMessage(SyncStateReceiver.State.CONNECTING,
                        getString(R.string.connecting_to_server), "")

            HtspConnection.ConnectionState.CLOSED ->
                sendSyncStateMessage(SyncStateReceiver.State.CLOSED,
                        getString(R.string.connection_closed), "")

            else -> {
            }
        }
    }

    private fun startAsyncCommunicationWithServer() {
        Timber.d("Starting async communication with server")

        pendingChannelOps.clear()
        pendingChannelTagOps.clear()
        pendingRecordingOps.clear()
        pendingEventOps.clear()

        initialSyncWithServerRunning = true

        val enableAsyncMetadataRequest = HtspMessage()
        enableAsyncMetadataRequest.method = "enableAsyncMetadata"


        val epgMaxTime = java.lang.Long.parseLong(sharedPreferences.getString("epg_max_time", appContext.resources.getString(R.string.pref_default_epg_max_time))!!)
        val currentTimeInSeconds = System.currentTimeMillis() / 1000L
        val lastUpdateTime = connection.lastUpdate

        syncRequired = connection.isSyncRequired
        Timber.d("Sync from server required: $syncRequired")
        syncEventsRequired = syncRequired || lastUpdateTime + epgMaxTime < currentTimeInSeconds
        Timber.d("Sync events from server required: $syncEventsRequired")

        // Send the first sync message to any broadcast listeners
        if (syncRequired || syncEventsRequired) {
            Timber.d("Sending status that sync has started")
            sendSyncStateMessage(SyncStateReceiver.State.SYNC_STARTED,
                    getString(R.string.loading_data), "")
        }
        if (syncEventsRequired) {
            Timber.d("Enabling requesting of epg data")
            Timber.d("Adding field to the enableAsyncMetadata request, epgMaxTime is ${(epgMaxTime + currentTimeInSeconds)}, lastUpdate time is ${(currentTimeInSeconds - 12 * 60 * 60)}")

            enableAsyncMetadataRequest["epg"] = 1
            enableAsyncMetadataRequest["epgMaxTime"] = epgMaxTime + currentTimeInSeconds
            // Only provide metadata that has changed since 12 hours ago.
            // The events past those 12 hours are not relevant and don't need to be sent by the server
            enableAsyncMetadataRequest["lastUpdate"] = currentTimeInSeconds - 12 * 60 * 60
        }

        htspConnection?.sendMessage(enableAsyncMetadataRequest, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                Timber.d("Received response for enableAsyncMetadata")
            }
        })
    }

    private fun onInitialSyncCompleted() {
        Timber.d("Received initial sync data from server")

        if (syncRequired) {
            sendSyncStateMessage(SyncStateReceiver.State.SYNC_IN_PROGRESS,
                    getString(R.string.saving_data), "")
        }

        // Save the channels and tags only during a forced sync.
        // This avoids the channel list being updated by the recyclerview
        if (syncRequired) {
            Timber.d("Sync of initial data is required, saving received channels, tags and downloading icons")
            saveAllReceivedChannels()
            saveAllReceivedChannelTags()
            loadAllChannelIcons()
        } else {
            Timber.d("Sync of initial data is not required")
        }

        // Only save any received events when they shall be loaded
        if (syncEventsRequired) {
            Timber.d("Sync of all evens is required, saving events")
            saveAllReceivedEvents()
        } else {
            Timber.d("Sync of all evens is not required")
        }

        // Recordings are always saved to keep up to
        // date with the recording states from the server
        saveAllReceivedRecordings()

        getAdditionalServerData()

        Timber.d("Updating connection status with full sync completed and last update time")
        connection.isSyncRequired = false
        connection.lastUpdate = System.currentTimeMillis() / 1000L
        appRepository.connectionData.updateItem(connection)

        // The initial sync is considered to be done at this point.
        // Send the message to the listeners that the sync is done
        if (syncRequired || syncEventsRequired) {
            sendSyncStateMessage(SyncStateReceiver.State.SYNC_DONE,
                    getString(R.string.loading_data_done), "")
        }

        syncRequired = false
        syncEventsRequired = false
        initialSyncWithServerRunning = false

        Timber.d("Deleting events in the database that are older than one day from now")
        val pastTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        appRepository.programData.removeItemsByTime(pastTime)

        Timber.d("Starting background worker to load more epg data")
        val updateEpgWorker = OneTimeWorkRequest.Builder(EpgDataUpdateWorker::class.java)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance().enqueueUniqueWork("UpdateEpg", ExistingWorkPolicy.REPLACE, updateEpgWorker)

        Timber.d("Done receiving initial data from server")
    }

    /**
     * Loads additional data from the server that is required after the initial sync is done.
     * This includes the disc space, the server system time and the playback and recording profiles.
     * If the server did not provide all required default profiles, then add them here.
     */
    private fun getAdditionalServerData() {
        Timber.d("Loading additional data from server")

        getDiscSpace()
        getSystemTime()
        getProfiles()
        getDvrConfigs()
        getHttpProfiles()

        addMissingHtspPlaybackProfileIfNotExists("htsp")
        addMissingHttpPlaybackProfileIfNotExists("matroska")
        addMissingHttpPlaybackProfileIfNotExists("audio")
        addMissingHttpPlaybackProfileIfNotExists("pass")

        setDefaultProfileSelection()
    }

    private fun getDiscSpace() {
        val request = HtspMessage()
        request.method = "getDiskSpace"
        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                onDiskSpace(response)
            }
        })
    }

    private fun getSystemTime() {
        val request = HtspMessage()
        request.method = "getSysTime"
        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                onSystemTime(response)
            }
        })
    }

    private fun getDvrConfigs() {
        val request = HtspMessage()
        request.method = "getDvrConfigs"
        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                onDvrConfigs(response)
            }
        })
    }

    private fun getProfiles() {
        val request = HtspMessage()
        request.method = "getProfiles"
        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                onHtspProfiles(response)
            }
        })
    }

    private fun getHttpProfiles() {
        if (htspVersion >= 26) {
            val request = HtspMessage()
            request.method = "api"
            request["path"] = "profile/list"
            htspConnection?.sendMessage(request, object : HtspResponseListener {
                override fun handleResponse(response: HtspMessage) {
                    onHttpProfiles(response)
                }
            })
        } else {
            Timber.d("Not requesting http profiles because the API version is too low")
        }
    }

    private fun addMissingHtspPlaybackProfileIfNotExists(name: String) {
        var profileExists = false

        val profileNames = appRepository.serverProfileData.htspPlaybackProfileNames
        for (profileName in profileNames) {
            if (profileName == name) {
                Timber.d("Default htsp playback profile $name exists already")
                profileExists = true
            }
        }
        if (!profileExists) {
            Timber.d("Default htsp playback profile $name does not exist, adding manually")
            val serverProfile = ServerProfile()
            serverProfile.connectionId = connection.id
            serverProfile.name = name
            serverProfile.type = "htsp_playback"
            appRepository.serverProfileData.addItem(serverProfile)
        }
    }

    private fun addMissingHttpPlaybackProfileIfNotExists(name: String) {
        var profileExists = false

        val profileNames = appRepository.serverProfileData.httpPlaybackProfileNames
        for (profileName in profileNames) {
            if (profileName == name) {
                Timber.d("Default http playback profile $name exists already")
                profileExists = true
            }
        }
        if (!profileExists) {
            Timber.d("Default http playback profile $name does not exist, adding manually")
            val serverProfile = ServerProfile()
            serverProfile.connectionId = connection.id
            serverProfile.name = name
            serverProfile.type = "http_playback"
            appRepository.serverProfileData.addItem(serverProfile)
        }
    }

    private fun setDefaultProfileSelection() {
        Timber.d("Setting default profiles in case none are selected yet")
        serverStatus?.let {
            if (it.htspPlaybackServerProfileId == 0) {
                for (profile in appRepository.serverProfileData.htspPlaybackProfiles) {
                    if (TextUtils.equals(profile.name, "htsp")) {
                        Timber.d("Setting htsp profile to htsp")
                        it.htspPlaybackServerProfileId = profile.id
                        break
                    }
                }
            }
            if (it.httpPlaybackServerProfileId == 0) {
                for (profile in appRepository.serverProfileData.httpPlaybackProfiles) {
                    if (TextUtils.equals(profile.name, "pass")) {
                        Timber.d("Setting http profile to pass")
                        it.httpPlaybackServerProfileId = profile.id
                        break
                    }
                }
            }
            if (it.recordingServerProfileId == 0) {
                for (profile in appRepository.serverProfileData.recordingProfiles) {
                    if (TextUtils.equals(profile.name, "Default Profile")) {
                        Timber.d("Setting recording profile to default")
                        it.recordingServerProfileId = profile.id
                        break
                    }
                }
            }
            appRepository.serverStatusData.updateItem(it)
        } ?: run {
            Timber.d("Server status is null, can't set default profile selections")
        }
    }

    /**
     * Server to client method.
     * A channel tag has been added on the server. Additionally to saving the new tag the
     * number of associated channels will be
     *
     * @param msg The message with the new tag data
     */
    private fun onTagAdd(msg: HtspMessage) {
        if (!initialSyncWithServerRunning) {
            return
        }

        // During initial sync no channels are yet saved. So use the temporarily
        // stored channels to calculate the channel count for the channel tag
        val addedTag = convertMessageToChannelTagModel(ChannelTag(), msg, pendingChannelOps)
        addedTag.connectionId = connection.id

        Timber.d("Sync is running, adding channel tag")
        pendingChannelTagOps.add(addedTag)
    }

    /**
     * Server to client method.
     * A tag has been updated on the server.
     *
     * @param msg The message with the updated tag data
     */
    private fun onTagUpdate(msg: HtspMessage) {
        if (!initialSyncWithServerRunning) {
            return
        }

        var channelTag = appRepository.channelTagData.getItemById(msg.getInteger("tagId"))
        if (channelTag == null) {
            Timber.d("Could not find a channel tag with id ${msg.getInteger("tagId")} in the database")
            channelTag = ChannelTag()
        }

        // During initial sync no channels are yet saved. So use the temporarily
        // stored channels to calculate the channel count for the channel tag
        val updatedTag = convertMessageToChannelTagModel(channelTag, msg, pendingChannelOps)
        updatedTag.connectionId = connection.id
        updatedTag.isSelected = channelTag.isSelected

        Timber.d("Sync is running, updating channel tag")
        pendingChannelTagOps.add(updatedTag)

        if (syncRequired && pendingChannelTagOps.size % 10 == 0) {
            sendSyncStateMessage(SyncStateReceiver.State.SYNC_IN_PROGRESS,
                    getString(R.string.receiving_data),
                    "Received ${pendingChannelTagOps.size} channel tags")
        }
    }

    /**
     * Server to client method.
     * A tag has been deleted on the server.
     *
     * @param msg The message with the tag id that was deleted
     */
    private fun onTagDelete(msg: HtspMessage) {
        if (msg.containsKey("tagId")) {
            val tag = appRepository.channelTagData.getItemById(msg.getInteger("tagId"))
            if (tag != null) {
                deleteIconFileFromCache(tag.tagIcon)
                appRepository.channelTagData.removeItem(tag)
                appRepository.tagAndChannelData.removeItemByTagId(tag.tagId)
            }
        }
    }

    /**
     * Server to client method.
     * A channel has been added on the server.
     *
     * @param msg The message with the new channel data
     */
    private fun onChannelAdd(msg: HtspMessage) {
        if (!initialSyncWithServerRunning) {
            return
        }

        val channel = convertMessageToChannelModel(Channel(), msg)
        channel.connectionId = connection.id
        channel.serverOrder = pendingChannelOps.size + 1

        Timber.d("Sync is running, adding channel name '${channel.name}', id '${channel.id}', number '${channel.displayNumber}', server order '${channel.serverOrder}")

        pendingChannelOps.add(channel)

        if (syncRequired && pendingChannelOps.size % 25 == 0) {
            sendSyncStateMessage(SyncStateReceiver.State.SYNC_IN_PROGRESS,
                    getString(R.string.receiving_data),
                    "Received ${pendingChannelOps.size} channels")
        }
    }

    /**
     * Server to client method.
     * A channel has been updated on the server.
     *
     * @param msg The message with the updated channel data
     */
    private fun onChannelUpdate(msg: HtspMessage) {
        if (!initialSyncWithServerRunning) {
            return
        }

        val channel = appRepository.channelData.getItemById(msg.getInteger("channelId"))
        if (channel == null) {
            Timber.d("Could not find a channel with id ${msg.getInteger("channelId")} in the database")
            return
        }
        val updatedChannel = convertMessageToChannelModel(channel, msg)
        appRepository.channelData.updateItem(updatedChannel)
    }

    /**
     * Server to client method.
     * A channel has been deleted on the server.
     *
     * @param msg The message with the channel id that was deleted
     */
    private fun onChannelDelete(msg: HtspMessage) {
        if (msg.containsKey("channelId")) {
            val channelId = msg.getInteger("channelId")

            val channel = appRepository.channelData.getItemById(channelId)
            if (channel != null) {
                deleteIconFileFromCache(channel.icon)
                appRepository.channelData.removeItemById(channel.id)
            }
        }
    }

    /**
     * Server to client method.
     * A recording has been added on the server.
     *
     * @param msg The message with the new recording data
     */
    private fun onDvrEntryAdd(msg: HtspMessage) {
        val recording = convertMessageToRecordingModel(Recording(), msg)
        recording.connectionId = connection.id

        if (initialSyncWithServerRunning) {
            pendingRecordingOps.add(recording)

            if (syncRequired && pendingRecordingOps.size % 25 == 0) {
                Timber.d("Sync is running, received ${pendingRecordingOps.size} recordings")
                sendSyncStateMessage(SyncStateReceiver.State.SYNC_IN_PROGRESS,
                        getString(R.string.receiving_data),
                        "Received ${pendingRecordingOps.size} recordings")
            }
        } else {
            appRepository.recordingData.addItem(recording)
        }

        addNotification(appContext, recording)
    }

    /**
     * Server to client method.
     * A recording has been updated on the server.
     *
     * @param msg The message with the updated recording data
     */
    private fun onDvrEntryUpdate(msg: HtspMessage) {
        // Get the existing recording
        val recording = appRepository.recordingData.getItemById(msg.getInteger("id"))
        if (recording == null) {
            Timber.d("Could not find a recording with id ${msg.getInteger("id")} in the database")
            return
        }
        val updatedRecording = convertMessageToRecordingModel(recording, msg)
        appRepository.recordingData.updateItem(updatedRecording)

        removeNotificationById(appContext, recording.id)
        if (sharedPreferences.getBoolean("notifications_enabled", appContext.resources.getBoolean(R.bool.pref_default_notifications_enabled))) {
            if (!recording.isScheduled && !recording.isRecording) {
                Timber.d("Removing notification for recording ${recording.title}")
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(recording.id)
            }
        }
    }

    /**
     * Server to client method.
     * A recording has been deleted on the server.
     *
     * @param msg The message with the recording id that was deleted
     */
    private fun onDvrEntryDelete(msg: HtspMessage) {
        if (msg.containsKey("id")) {
            val recording = appRepository.recordingData.getItemById(msg.getInteger("id"))
            if (recording != null) {
                appRepository.recordingData.removeItem(recording)

            }
        }
    }

    /**
     * Server to client method.
     * A series recording has been added on the server.
     *
     * @param msg The message with the new series recording data
     */
    private fun onAutorecEntryAdd(msg: HtspMessage) {
        val seriesRecording = convertMessageToSeriesRecordingModel(SeriesRecording(), msg)
        seriesRecording.connectionId = connection.id
        appRepository.seriesRecordingData.addItem(seriesRecording)
    }

    /**
     * Server to client method.
     * A series recording has been updated on the server.
     *
     * @param msg The message with the updated series recording data
     */
    private fun onAutorecEntryUpdate(msg: HtspMessage) {
        val id = msg.getString("id", "")
        if (id.isEmpty()) {
            Timber.d("Could not find a series recording with id $id in the database")
            return
        }
        val recording = appRepository.seriesRecordingData.getItemById(msg.getString("id"))
        val updatedRecording = convertMessageToSeriesRecordingModel(recording, msg)
        appRepository.seriesRecordingData.updateItem(updatedRecording)
    }

    /**
     * Server to client method.
     * A series recording has been deleted on the server.
     *
     * @param msg The message with the series recording id that was deleted
     */
    private fun onAutorecEntryDelete(msg: HtspMessage) {
        val id = msg.getString("id", "")
        if (!id.isEmpty()) {
            val seriesRecording = appRepository.seriesRecordingData.getItemById(msg.getString("id"))
            appRepository.seriesRecordingData.removeItem(seriesRecording)
        }
    }

    /**
     * Server to client method.
     * A timer recording has been added on the server.
     *
     * @param msg The message with the new timer recording data
     */
    private fun onTimerRecEntryAdd(msg: HtspMessage) {
        val recording = convertMessageToTimerRecordingModel(TimerRecording(), msg)
        recording.connectionId = connection.id
        appRepository.timerRecordingData.addItem(recording)
    }

    /**
     * Server to client method.
     * A timer recording has been updated on the server.
     *
     * @param msg The message with the updated timer recording data
     */
    private fun onTimerRecEntryUpdate(msg: HtspMessage) {
        val id = msg.getString("id", "")
        if (id.isEmpty()) {
            Timber.d("Could not find a timer recording with id $id in the database")
            return
        }
        val recording = appRepository.timerRecordingData.getItemById(id)
        val updatedRecording = convertMessageToTimerRecordingModel(recording, msg)
        appRepository.timerRecordingData.updateItem(updatedRecording)
    }

    /**
     * Server to client method.
     * A timer recording has been deleted on the server.
     *
     * @param msg The message with the recording id that was deleted
     */
    private fun onTimerRecEntryDelete(msg: HtspMessage) {
        val id = msg.getString("id", "")
        if (!id.isEmpty()) {
            val timerRecording = appRepository.timerRecordingData.getItemById(id)
            appRepository.timerRecordingData.removeItem(timerRecording)
        }
    }

    /**
     * Server to client method.
     * An epg event has been added on the server.
     *
     * @param msg The message with the new epg event data
     */
    private fun onEventAdd(msg: HtspMessage) {
        if (!firstEventReceived && syncRequired) {
            Timber.d("Sync is required and received first event, saving ${pendingChannelOps.size} channels")
            appRepository.channelData.addItems(pendingChannelOps)

            Timber.d("Updating connection status with full sync completed")
            connection.isSyncRequired = false
            appRepository.connectionData.updateItem(connection)
        }

        firstEventReceived = true
        val program = convertMessageToProgramModel(Program(), msg)
        program.connectionId = connection.id

        if (initialSyncWithServerRunning) {
            pendingEventOps.add(program)

            if (syncRequired && pendingEventOps.size % 50 == 0) {
                Timber.d("Sync is running, received ${pendingEventOps.size} program guide events")
                sendSyncStateMessage(SyncStateReceiver.State.SYNC_IN_PROGRESS,
                        getString(R.string.receiving_data),
                        "Received ${pendingEventOps.size} program guide events")
            }
        } else {
            Timber.d("Adding event ${program.title}")
            appRepository.programData.addItem(program)
        }
    }

    /**
     * Server to client method.
     * An epg event has been updated on the server.
     *
     * @param msg The message with the updated epg event data
     */
    private fun onEventUpdate(msg: HtspMessage) {
        val program = appRepository.programData.getItemById(msg.getInteger("eventId"))
        if (program == null) {
            Timber.d("Could not find a program with id ${msg.getInteger("eventId")} in the database")
            return
        }
        val updatedProgram = convertMessageToProgramModel(program, msg)
        Timber.d("Updating event ${updatedProgram.title}")
        appRepository.programData.updateItem(updatedProgram)
    }

    /**
     * Server to client method.
     * An epg event has been deleted on the server.
     *
     * @param msg The message with the epg event id that was deleted
     */
    private fun onEventDelete(msg: HtspMessage) {
        if (msg.containsKey("id")) {
            appRepository.programData.removeItemById(msg.getInteger("id"))
        }
    }

    /**
     * Handles the given server message that contains a list of events.
     *
     * @param message The message with the events
     */
    private fun onGetEvents(message: HtspMessage, intent: Intent) {

        val useEventList = intent.getBooleanExtra("useEventList", false)
        val channelName = intent.getStringExtra("channelName")

        if (message.containsKey("events")) {
            val programs = ArrayList<Program>()
            for (obj in message.getList("events")) {
                val msg = obj as HtspMessage
                val program = convertMessageToProgramModel(Program(), msg)
                program.connectionId = connection.id
                programs.add(program)
            }

            if (useEventList) {
                Timber.d("Adding ${programs.size} events to the list for channel $channelName")
                pendingEventOps.addAll(programs)
            } else {
                Timber.d("Saving ${programs.size} events for channel $channelName")
                appRepository.programData.addItems(programs)
            }
        }
    }

    private fun onHtspProfiles(message: HtspMessage) {
        Timber.d("Handling htsp playback profiles")
        if (message.containsKey("profiles")) {
            for (obj in message.getList("profiles")) {
                val msg = obj as HtspMessage
                val name = msg.getString("name")

                val profileNames = appRepository.serverProfileData.htspPlaybackProfileNames
                var profileExists = false
                for (profileName in profileNames) {
                    if (profileName == name) {
                        profileExists = true
                        break
                    }
                }
                if (!profileExists) {
                    val serverProfile = ServerProfile()
                    serverProfile.connectionId = connection.id
                    serverProfile.name = name
                    serverProfile.uuid = msg.getString("uuid")
                    serverProfile.comment = msg.getString("comment")
                    serverProfile.type = "htsp_playback"

                    Timber.d("Adding htsp playback profile ${serverProfile.name}")
                    appRepository.serverProfileData.addItem(serverProfile)
                }
            }
        }
    }

    private fun onHttpProfiles(message: HtspMessage) {
        Timber.d("Handling http playback profiles")
        if (message.containsKey("response")) {
            try {
                val response = JSONObject(message.getString("response"))
                if (response.has("entries")) {
                    val entries = response.getJSONArray("entries")
                    if (entries.length() > 0) {
                        var i = 0
                        val totalObject = entries.length()
                        while (i < totalObject) {
                            val profile = entries.getJSONObject(i)
                            if (profile.has("key") && profile.has("val")) {
                                val name = profile.getString("val")

                                val profileNames = appRepository.serverProfileData.httpPlaybackProfileNames
                                var profileExists = false
                                for (profileName in profileNames) {
                                    if (profileName == name) {
                                        profileExists = true
                                        break
                                    }
                                }
                                if (!profileExists) {
                                    val serverProfile = ServerProfile()
                                    serverProfile.connectionId = connection.id
                                    serverProfile.name = name
                                    serverProfile.uuid = profile.getString("key")
                                    serverProfile.type = "http_playback"

                                    Timber.d("Adding http playback profile ${serverProfile.name}")
                                    appRepository.serverProfileData.addItem(serverProfile)
                                }
                            }
                            i++
                        }
                    }
                }
            } catch (e: JSONException) {
                Timber.d(e, "Error parsing JSON data")
            }

        }
    }

    private fun onDvrConfigs(message: HtspMessage) {
        Timber.d("Handling recording profiles")
        if (message.containsKey("dvrconfigs")) {
            for (obj in message.getList("dvrconfigs")) {
                val msg = obj as HtspMessage
                var serverProfile = appRepository.serverProfileData.getItemById(msg.getString("uuid"))
                if (serverProfile == null) {
                    serverProfile = ServerProfile()
                }

                serverProfile.connectionId = connection.id
                serverProfile.uuid = msg.getString("uuid")
                val name = msg.getString("name")
                serverProfile.name = if (TextUtils.isEmpty(name)) "Default Profile" else name
                serverProfile.comment = msg.getString("comment")
                serverProfile.type = "recording"

                if (serverProfile.id == 0) {
                    Timber.d("Added new recording profile ${serverProfile.name}")
                    appRepository.serverProfileData.addItem(serverProfile)
                } else {
                    Timber.d("Updated existing recording profile ${serverProfile.name}")
                    appRepository.serverProfileData.updateItem(serverProfile)
                }
            }
        }
    }

    private fun onServerStatus(message: HtspMessage) {
        serverStatus?.let {
            val updatedServerStatus = convertMessageToServerStatusModel(it, message)
            updatedServerStatus.connectionId = connection.id
            updatedServerStatus.connectionName = connection.name
            Timber.d("Received initial response from server ${updatedServerStatus.serverName}, api version: ${updatedServerStatus.htspVersion}")

            appRepository.serverStatusData.updateItem(updatedServerStatus)
        } ?: run {
            Timber.d("Server status is null, can't update server status")
        }
    }

    private fun onSystemTime(message: HtspMessage) {
        serverStatus?.let {
            val gmtOffsetFromServer = message.getInteger("gmtoffset", 0) * 60 * 1000
            val gmtOffset = gmtOffsetFromServer - daylightSavingOffset
            Timber.d("GMT offset from server is $gmtOffsetFromServer, GMT offset considering daylight saving offset is $gmtOffset")

            it.gmtoffset = gmtOffset
            it.time = message.getLong("time", 0)
            appRepository.serverStatusData.updateItem(it)

            Timber.d("Received system time from server ${it.serverName}, server time: ${it.time}, server gmt offset: ${it.gmtoffset}")
        } ?: run {
            Timber.d("Server status is null, can't update system time")
        }
    }

    private fun onDiskSpace(message: HtspMessage) {
        serverStatus?.let {
            it.freeDiskSpace = message.getLong("freediskspace", 0)
            it.totalDiskSpace = message.getLong("totaldiskspace", 0)
            appRepository.serverStatusData.updateItem(it)

            Timber.d("Received disk space information from server ${it.serverName}, free disk space: ${it.freeDiskSpace}, total disk space: ${it.totalDiskSpace}")
        } ?: run {
            Timber.d("Server status is null, can't update disc space")
        }
    }

    /**
     * Saves all received channels from the initial sync in the database.
     */
    private fun saveAllReceivedChannels() {
        Timber.d("Saving ${pendingChannelOps.size} channels")

        if (!pendingChannelOps.isEmpty()) {
            appRepository.channelData.addItems(pendingChannelOps)
        }
    }

    /**
     * Saves all received channel tags from the initial sync in the database.
     * Also the relations table between channels and tags are
     * updated so that the filtering by channel tags works properly
     */
    private fun saveAllReceivedChannelTags() {
        Timber.d("Saving ${pendingChannelTagOps.size} channel tags")

        val pendingRemovedTagAndChannelOps = ArrayList<TagAndChannel>()
        val pendingAddedTagAndChannelOps = ArrayList<TagAndChannel>()

        if (!pendingChannelTagOps.isEmpty()) {
            appRepository.channelTagData.addItems(pendingChannelTagOps)
            for (tag in pendingChannelTagOps) {

                val tac = appRepository.tagAndChannelData.getItemById(tag.tagId)
                if (tac != null) {
                    pendingRemovedTagAndChannelOps.add(tac)
                }

                val channelIds = tag.members
                if (channelIds != null) {
                    for (channelId in channelIds) {
                        val tagAndChannel = TagAndChannel()
                        tagAndChannel.tagId = tag.tagId
                        tagAndChannel.channelId = channelId
                        tagAndChannel.connectionId = connection.id
                        pendingAddedTagAndChannelOps.add(tagAndChannel)
                    }
                }
            }

            Timber.d("Removing ${pendingRemovedTagAndChannelOps.size} and adding ${pendingAddedTagAndChannelOps.size} tag and channel relations")
            appRepository.tagAndChannelData.addAndRemoveItems(pendingAddedTagAndChannelOps, pendingRemovedTagAndChannelOps)
        }
    }

    /**
     * Removes all recordings and saves all received recordings from the initial sync
     * in the database. The removal is done to prevent being out of sync with the server.
     * This could be the case when the app was offline for a while and it did not receive
     * any recording removal information from the server. During the initial sync the
     * server only provides the list of available recordings.
     */
    private fun saveAllReceivedRecordings() {
        Timber.d("Removing previously existing recordings and saving ${pendingRecordingOps.size} new recordings")
        appRepository.recordingData.removeItems()
        if (!pendingRecordingOps.isEmpty()) {
            appRepository.recordingData.addItems(pendingRecordingOps)
        }
    }

    private fun saveAllReceivedEvents() {
        Timber.d("Saving ${pendingEventOps.size} new events")
        if (!pendingEventOps.isEmpty()) {
            appRepository.programData.addItems(pendingEventOps)
        }
    }

    /**
     * Tries to download and save all received channel and channel
     * tag logos from the initial sync in the database.
     */
    private fun loadAllChannelIcons() {
        Timber.d("Downloading and saving all channel and channel tag icons...")

        for (channel in appRepository.channelData.getItems()) {
            execService.execute {
                try {
                    Timber.d("Downloading channel icon for channel ${channel.name}")
                    downloadIconFromFileUrl(channel.icon)
                } catch (e: Exception) {
                    Timber.d("Could not load channel icon for channel '${channel.icon}'")
                }
            }
        }
        for (tag in appRepository.channelTagData.getItems()) {
            execService.execute {
                try {
                    Timber.d("Downloading channel icon for channel tag ${tag.tagName}")
                    downloadIconFromFileUrl(tag.tagIcon)
                } catch (e: Exception) {
                    Timber.d("Could not load channel tag icon '${tag.tagIcon}'")
                }
            }
        }
    }

    /**
     * Downloads the file from the given url. If the url starts with http then a
     * buffered input stream is used, otherwise the htsp api is used. The file
     * will be saved in the cache directory using a unique hash value as the file name.
     *
     * @param url The url of the file that shall be downloaded
     * @throws IOException Error message if something went wrong
     */
    // Use the icon loading from the original library?
    @Throws(IOException::class)
    private fun downloadIconFromFileUrl(url: String?) {

        if (url.isNullOrEmpty()) {
            return
        }

        val file = File(cacheDir, convertUrlToHashString(url) + ".png")
        if (file.exists()) {
            Timber.d("Icon file ${file.absolutePath} exists already")
            return
        }

        var inputStream: InputStream
        when {
            url.startsWith("http") -> inputStream = BufferedInputStream(URL(url).openStream())
            htspVersion > 9 -> inputStream = HtspFileInputStream(htspConnection, url)
            else -> return
        }

        val os = FileOutputStream(file)

        // Set the options for a bitmap and decode an input stream into a bitmap
        var o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeStream(inputStream, null, o)
        inputStream.close()

        if (url.startsWith("http")) {
            inputStream = BufferedInputStream(URL(url).openStream())
        } else if (htspVersion > 9) {
            inputStream = HtspFileInputStream(htspConnection, url)
        }

        val scale = appContext.resources.displayMetrics.density
        val width = (64 * scale).toInt()
        val height = (64 * scale).toInt()

        // Set the sample size of the image. This is the number of pixels in
        // either dimension that correspond to a single pixel in the decoded
        // bitmap. For example, inSampleSize == 4 returns an image that is 1/4
        // the width/height of the original, and 1/16 the number of pixels.
        val ratio = Math.max(o.outWidth / width, o.outHeight / height)
        val sampleSize = Integer.highestOneBit(Math.floor(ratio.toDouble()).toInt())
        o = BitmapFactory.Options()
        o.inSampleSize = sampleSize

        // Now decode an input stream into a bitmap and compress it.
        val bitmap = BitmapFactory.decodeStream(inputStream, null, o)
        bitmap?.compress(Bitmap.CompressFormat.PNG, 100, os)

        os.close()
        inputStream.close()
    }

    /**
     * Removes the cached image file from the file system
     *
     * @param iconUrl The icon url
     */
    private fun deleteIconFileFromCache(iconUrl: String?) {
        if (TextUtils.isEmpty(iconUrl)) {
            return
        }
        val url = getIconUrl(appContext, iconUrl)
        val file = File(url)
        if (!file.exists() || !file.delete()) {
            Timber.d("Could not delete icon ${file.name}")
        }
    }

    private fun getChannel(intent: Intent) {
        val request = HtspMessage()
        request["method"] = "getChannel"
        request["channelId"] = intent.getIntExtra("channelId", 0)

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                // Update the icon if required
                val icon = response.getString("channelIcon", null)
                if (icon != null) {
                    try {
                        downloadIconFromFileUrl(icon)
                    } catch (e: Exception) {
                        Timber.d("Could not load icon '$icon'")
                    }

                }
            }
        })
    }

    private fun getEvent(intent: Intent) {
        val request = HtspMessage()
        request["method"] = "getEvent"
        request["eventId"] = intent.getIntExtra("eventId", 0)

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                val program = convertMessageToProgramModel(Program(), response)
                appRepository.programData.addItem(program)
            }
        })
    }

    /**
     * Request information about a set of events from the server.
     * If no options are specified the entire EPG database will be returned.
     *
     * @param intent Intent with the request message fields
     */
    private fun getEvents(intent: Intent) {
        val showMessage = intent.getBooleanExtra("showMessage", false)
        val request = convertIntentToEventMessage(intent)
        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                onGetEvents(response, intent)
                if (showMessage) {
                    Timber.d("Showing message")
                    sendSnackbarMessage(appContext, getString(R.string.loading_more_programs_finished))
                }
            }
        })
    }

    /**
     * Loads a defined number of events for all channels.
     * This method is called by a worker after the initial sync is done.
     * All loaded events are saved in a temporary list and saved in one
     * batch into the database when all events were loaded for all channels.
     *
     * @param intent The intent with the parameters e.g. to define how many events shall be loaded
     */
    private fun getMoreEvents(intent: Intent) {

        val numberOfProgramsToLoad = intent.getIntExtra("numFollowing", 0)
        val channelList = appRepository.channelData.getItems()

        Timber.d("Database currently contains ${appRepository.programData.itemCount} events. ")
        Timber.d("Loading $numberOfProgramsToLoad events for each of the ${channelList.size} channels")

        for (channel in channelList) {
            val lastProgram = appRepository.programData.getLastItemByChannelId(channel.id)

            val msgIntent = Intent()
            msgIntent.putExtra("numFollowing", numberOfProgramsToLoad)
            msgIntent.putExtra("useEventList", true)
            msgIntent.putExtra("channelId", channel.id)
            msgIntent.putExtra("channelName", channel.name)

            when {
                lastProgram != null -> {
                    Timber.d("Loading more programs for channel ${channel.name} from last program id ${lastProgram.eventId}")
                    msgIntent.putExtra("eventId", lastProgram.nextEventId)
                }
                channel.nextEventId > 0 -> {
                    Timber.d("Loading more programs for channel ${channel.name} starting from channel next event id ${channel.nextEventId}")
                    msgIntent.putExtra("eventId", channel.nextEventId)
                }
                else -> {
                    Timber.d("Loading more programs for channel ${channel.name} starting from channel event id ${channel.eventId}")
                    msgIntent.putExtra("eventId", channel.eventId)
                }
            }
            getEvents(msgIntent)
        }

        appRepository.programData.addItems(pendingEventOps)
        Timber.d("Saved ${pendingEventOps.size} events for all channels. Database contains ${appRepository.programData.itemCount} events")
        pendingEventOps.clear()
    }

    private fun getEpgQuery(intent: Intent) {
        val request = convertIntentToEpgQueryMessage(intent)
        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                // Contains the ids of those events that were returned by the query
                val eventIdList = ArrayList<Int>()
                if (response.containsKey("events")) {
                    // List of events that match the query. Add the eventIds
                    for (obj in response.getList("events")) {
                        val msg = obj as HtspMessage
                        eventIdList.add(msg.getInteger("eventId"))
                    }
                } else if (response.containsKey("eventIds")) {
                    // List of eventIds that match the query
                    for (obj in response.getArrayList("eventIds")) {
                        eventIdList.add(obj as Int)
                    }
                }
            }
        })
    }

    private fun addDvrEntry(intent: Intent) {
        val request = convertIntentToDvrMessage(intent, htspVersion)
        request["method"] = "addDvrEntry"

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                if (response.getInteger("success", 0) == 1) {
                    sendSnackbarMessage(applicationContext, getString(R.string.success_adding_recording))
                } else {
                    sendSnackbarMessage(applicationContext, getString(R.string.error_adding_recording, response.getString("error", "")))
                }
            }
        })

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(intent.getIntExtra("eventId", 0))
    }

    private fun updateDvrEntry(intent: Intent) {
        val request = convertIntentToDvrMessage(intent, htspVersion)
        request["method"] = "updateDvrEntry"
        request["id"] = intent.getIntExtra("id", 0)

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                if (response.getInteger("success", 0) == 1) {
                    sendSnackbarMessage(applicationContext, getString(R.string.success_updating_recording))
                } else {
                    sendSnackbarMessage(applicationContext, getString(R.string.error_updating_recording, response.getString("error", "")))
                }
            }
        })
    }

    private fun removeDvrEntry(intent: Intent) {
        val request = HtspMessage()
        request["method"] = intent.action
        request["id"] = intent.getIntExtra("id", 0)

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                Timber.d("Response is not null")
                if (response.getInteger("success", 0) == 1) {
                    sendSnackbarMessage(applicationContext, getString(R.string.success_removing_recording))
                } else {
                    sendSnackbarMessage(applicationContext, getString(R.string.error_removing_recording, response.getString("error", "")))
                }
            }
        })

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(intent.getIntExtra("id", 0))
    }

    private fun addAutorecEntry(intent: Intent) {
        val request = convertIntentToAutorecMessage(intent, htspVersion)
        request["method"] = "addAutorecEntry"

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                if (response.getInteger("success", 0) == 1) {
                    sendSnackbarMessage(applicationContext, getString(R.string.success_adding_recording))
                } else {
                    sendSnackbarMessage(applicationContext, getString(R.string.error_adding_recording, response.getString("error", "")))
                }
            }
        })
    }

    private fun updateAutorecEntry(intent: Intent) {
        var request = HtspMessage()
        if (htspVersion >= 25) {
            request = convertIntentToAutorecMessage(intent, htspVersion)
            request["method"] = "updateAutorecEntry"
        } else {
            request["method"] = "deleteAutorecEntry"
        }
        request["id"] = intent.getStringExtra("id")

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                // Handle the response here because the "updateAutorecEntry" call does
                // not exist on the server. First delete the entry and if this was
                // successful add a new entry with the new values.
                val success = response.getInteger("success", 0) == 1
                if (htspVersion < 25 && success) {
                    addAutorecEntry(intent)
                } else {
                    if (success) {
                        sendSnackbarMessage(applicationContext, getString(R.string.success_updating_recording))
                    } else {
                        sendSnackbarMessage(applicationContext, getString(R.string.error_updating_recording, response.getString("error", "")))
                    }
                }
            }
        })
    }

    private fun deleteAutorecEntry(intent: Intent) {
        val request = HtspMessage()
        request["method"] = "deleteAutorecEntry"
        request["id"] = intent.getStringExtra("id")

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                if (response.getInteger("success", 0) == 1) {
                    sendSnackbarMessage(applicationContext, getString(R.string.success_removing_recording))
                } else {
                    sendSnackbarMessage(applicationContext, getString(R.string.error_removing_recording, response.getString("error", "")))
                }
            }
        })
    }

    private fun addTimerrecEntry(intent: Intent) {
        val request = convertIntentToTimerecMessage(intent, htspVersion)
        request["method"] = "addTimerecEntry"

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                if (response.getInteger("success", 0) == 1) {
                    sendSnackbarMessage(applicationContext, getString(R.string.success_adding_recording))
                } else {
                    sendSnackbarMessage(applicationContext, getString(R.string.error_adding_recording, response.getString("error", "")))
                }
            }
        })
    }

    private fun updateTimerrecEntry(intent: Intent) {
        var request = HtspMessage()
        if (htspVersion >= 25) {
            request = convertIntentToTimerecMessage(intent, htspVersion)
            request["method"] = "updateTimerecEntry"
        } else {
            request["method"] = "deleteTimerecEntry"
        }
        request["id"] = intent.getStringExtra("id")

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                // Handle the response here because the "updateTimerecEntry" call does
                // not exist on the server. First delete the entry and if this was
                // successful add a new entry with the new values.
                val success = response.getInteger("success", 0) == 1
                if (htspVersion < 25 && success) {
                    addTimerrecEntry(intent)
                } else {
                    if (success) {
                        sendSnackbarMessage(applicationContext, getString(R.string.success_updating_recording))
                    } else {
                        sendSnackbarMessage(applicationContext, getString(R.string.error_updating_recording, response.getString("error", "")))
                    }
                }
            }
        })
    }

    private fun deleteTimerrecEntry(intent: Intent) {
        val request = HtspMessage()
        request["method"] = "deleteTimerecEntry"
        request["id"] = intent.getStringExtra("id")

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                if (response.getInteger("success", 0) == 1) {
                    sendSnackbarMessage(applicationContext, getString(R.string.success_removing_recording))
                } else {
                    sendSnackbarMessage(applicationContext, getString(R.string.error_removing_recording, response.getString("error", "")))
                }
            }
        })
    }

    private fun getTicket(intent: Intent) {
        val channelId = intent.getIntExtra("channelId", 0).toLong()
        val dvrId = intent.getIntExtra("dvrId", 0).toLong()

        val request = HtspMessage()
        request["method"] = "getTicket"
        if (channelId > 0) {
            request["channelId"] = channelId
        }
        if (dvrId > 0) {
            request["dvrId"] = dvrId
        }

        htspConnection?.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                Timber.d("Response is not null")
                val ticketIntent = Intent("ticket")
                ticketIntent.putExtra("path", response.getString("path"))
                ticketIntent.putExtra("ticket", response.getString("ticket"))
                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(ticketIntent)
            }
        })
    }

    private fun sendSyncStateMessage(state: SyncStateReceiver.State, message: String, details: String?) {
        val intent = Intent(SyncStateReceiver.ACTION)
        intent.putExtra(SyncStateReceiver.STATE, state)
        if (!TextUtils.isEmpty(message)) {
            intent.putExtra(SyncStateReceiver.MESSAGE, message)
        }
        if (!TextUtils.isEmpty(details)) {
            intent.putExtra(SyncStateReceiver.DETAILS, details)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}

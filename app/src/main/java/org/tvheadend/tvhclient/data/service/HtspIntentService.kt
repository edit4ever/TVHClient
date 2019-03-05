package org.tvheadend.tvhclient.data.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import androidx.core.app.JobIntentService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.tvheadend.tvhclient.MainApplication
import org.tvheadend.tvhclient.data.repository.AppRepository
import org.tvheadend.tvhclient.data.service.htsp.HtspConnection
import org.tvheadend.tvhclient.data.service.htsp.HtspConnectionStateListener
import org.tvheadend.tvhclient.data.service.htsp.HtspFileInputStream
import org.tvheadend.tvhclient.data.service.htsp.HtspMessage
import org.tvheadend.tvhclient.domain.entity.Connection
import org.tvheadend.tvhclient.domain.entity.Program
import org.tvheadend.tvhclient.util.MiscUtils
import timber.log.Timber
import java.io.*
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.inject.Inject

class HtspIntentService : JobIntentService(), HtspConnectionStateListener {

    private val execService: ScheduledExecutorService
    private var htspConnection: HtspConnection
    private var connection: Connection? = null
    private val htspVersion: Int

    @Inject
    lateinit var appRepository: AppRepository
    @Inject
    lateinit var sharedPreferences: SharedPreferences

    private val pendingEventOps = ArrayList<Program>()
    private val authenticationLock = Object()
    private val responseLock = Object()

    init {
        MainApplication.getComponent().inject(this)
        execService = Executors.newScheduledThreadPool(10)
        connection = appRepository.connectionData.activeItem
        htspVersion = appRepository.serverStatusData.activeItem?.htspVersion ?: 13
        htspConnection = HtspConnection(this, null)
        // Since this is blocking, spawn to a new thread
        execService.execute {
            htspConnection.openConnection()
            htspConnection.authenticate()
        }
    }

    override fun onHandleWork(intent: Intent) {

        val action = intent.action
        if (action == null || action.isEmpty()) {
            return
        }

        synchronized(authenticationLock) {
            try {
                authenticationLock.wait(5000)
            } catch (e: InterruptedException) {
                Timber.d("Timeout waiting while connecting to server")
            }

        }

        if (htspConnection.isNotConnected || !htspConnection.isAuthenticated) {
            Timber.d("Connection to server failed or authentication failed")
            return
        }

        Timber.d("Executing command $action for service")
        when (action) {
            "getMoreEvents" -> getMoreEvents(intent)
            "loadChannelIcons" -> loadAllChannelIcons()
            "getTicket" -> getTicket(intent)
        }
    }

    override fun onDestroy() {
        Timber.d("Stopping service")
        execService.shutdown()
        htspConnection.closeConnection()
        connection = null
    }

    override fun onAuthenticationStateChange(state: HtspConnection.AuthenticationState) {
        Timber.d("Authentication state changed to $state")
        synchronized(authenticationLock) {
            authenticationLock.notify()
        }
    }

    override fun onConnectionStateChange(state: HtspConnection.ConnectionState) {
        // NOP
    }

    /**
     * Handles the given server message that contains a list of events.
     *
     * @param message The message with the events
     */
    private fun onGetEvents(message: HtspMessage, intent: Intent) {
        val channelName = intent.getStringExtra("channelName")

        if (message.containsKey("events")) {
            val programs = ArrayList<Program>()
            for (obj in message.getList("events")) {
                val msg = obj as HtspMessage
                val program = HtspUtils.convertMessageToProgramModel(Program(), msg)
                program.connectionId = connection!!.id

                programs.add(program)
            }
            Timber.d("Added " + programs.size + " events to the list for channel " + channelName)
            pendingEventOps.addAll(programs)
        }
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

        htspConnection.sendMessage(request) { response ->
            if (response != null) {
                Timber.d("Response is not null")
                val ticketIntent = Intent("ticket")
                ticketIntent.putExtra("path", response.getString("path"))
                ticketIntent.putExtra("ticket", response.getString("ticket"))
                LocalBroadcastManager.getInstance(this).sendBroadcast(ticketIntent)
            } else {
                Timber.d("Response is null")
            }
        }
    }

    /**
     * Tries to download and save all received channel and channel
     * tag logos from the initial sync in the database.
     */
    private fun loadAllChannelIcons() {
        Timber.d("Downloading and saving all channel and channel tag icons...")

        val iconUrls = ArrayList<String>()
        appRepository.channelData.getItems().forEach {
            val icon = it.icon
            if (!icon.isNullOrEmpty()) {
                iconUrls.add(icon)
            }
        }

        appRepository.channelTagData.getItems().forEach {
            val icon = it.tagIcon
            if (!icon.isNullOrEmpty()) {
                iconUrls.add(icon)
            }
        }

        var iconUrlCount = 0
        iconUrls.forEach {
            // Determine if events for the last channel in the list are being loaded.
            // This is required to set and release a lock to get all responses
            // before saving the event data
            val isLastIconUrl = ++iconUrlCount == iconUrls.size
            execService.execute {
                try {
                    Timber.d("Downloading icon url $it")
                    downloadIconFromFileUrl(it)
                    // Release the lock so that all data can be saved
                    if (isLastIconUrl) {
                        synchronized(responseLock) {
                            Timber.d("Got response for last icon, releasing lock")
                            responseLock.notify()
                        }
                    }
                } catch (e: Exception) {
                    Timber.d("Could not load icon $it")
                }
            }

            // Wait until the last response from the server was received and the lock released
            if (isLastIconUrl) {
                synchronized(responseLock) {
                    try {
                        Timber.d("Loaded icons, waiting for response")
                        responseLock.wait(5000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

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
    private fun downloadIconFromFileUrl(url: String) {
        if (TextUtils.isEmpty(url)) {
            return
        }

        val file = File(cacheDir, MiscUtils.convertUrlToHashString(url)!! + ".png")
        if (file.exists()) {
            Timber.d("Icon file " + file.absolutePath + " exists already")
            return
        }

        var inputStream: InputStream
        when {
            url.startsWith("http") -> inputStream = BufferedInputStream(URL(url).openStream())
            htspVersion > 9 -> inputStream = HtspFileInputStream(htspConnection, url)
            else -> return
        }

        val outputStream = FileOutputStream(file)

        // Set the options for a bitmap and decode an input stream into a bitmap
        var options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        if (url.startsWith("http")) {
            inputStream = BufferedInputStream(URL(url).openStream())
        } else if (htspVersion > 9) {
            inputStream = HtspFileInputStream(htspConnection, url)
        }

        val scale = resources.displayMetrics.density
        val width = (64 * scale).toInt()
        val height = (64 * scale).toInt()

        // Set the sample size of the image. This is the number of pixels in
        // either dimension that correspond to a single pixel in the decoded
        // bitmap. For example, inSampleSize == 4 returns an image that is 1/4
        // the width/height of the original, and 1/16 the number of pixels.
        val ratio = Math.max(options.outWidth / width, options.outHeight / height)
        val sampleSize = Integer.highestOneBit(Math.floor(ratio.toDouble()).toInt())
        options = BitmapFactory.Options()
        options.inSampleSize = sampleSize

        // Now decode an input stream into a bitmap and compress it.
        val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        bitmap?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)

        outputStream.close()
        inputStream.close()
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

        Timber.d("Database currently contains " + appRepository.programData.itemCount + " events. ")
        Timber.d("Loading " + numberOfProgramsToLoad + " events for each of the " + channelList.size + " channels")

        var channelCount = 0
        channelList.forEach {
            // Determine if events for the last channel in the list are being loaded.
            // This is required to set and release a lock to get all responses
            // before saving the event data
            val isLastChannel = ++channelCount == channelList.size

            val msgIntent = Intent()
            msgIntent.putExtra("numFollowing", numberOfProgramsToLoad)
            msgIntent.putExtra("channelId", it.id)
            msgIntent.putExtra("channelName", it.name)

            val lastProgram = appRepository.programData.getLastItemByChannelId(it.id)
            when {
                lastProgram != null -> {
                    Timber.d("Loading more programs for channel ${it.name} from last program id ${lastProgram.eventId}")
                    msgIntent.putExtra("eventId", lastProgram.nextEventId)
                }
                it.nextEventId > 0 -> {
                    Timber.d("Loading more programs for channel ${it.name} starting from channel next event id ${it.nextEventId}")
                    msgIntent.putExtra("eventId", it.nextEventId)
                }
                else -> {
                    Timber.d("Loading more programs for channel ${it.name} starting from channel event id ${it.eventId}")
                    msgIntent.putExtra("eventId", it.eventId)
                }
            }

            val request = HtspUtils.convertIntentToEventMessage(msgIntent)
            htspConnection.sendMessage(request) { response ->
                if (response != null) {
                    onGetEvents(response, msgIntent)
                    // Release the lock so that all data can be saved
                    if (isLastChannel) {
                        synchronized(responseLock) {
                            Timber.d("Got response for last channel, releasing lock")
                            responseLock.notify()
                        }
                    }
                } else {
                    Timber.d("Response is null")
                }
            }

            // Wait until the last response from the server was received and the lock released
            if (isLastChannel) {
                synchronized(responseLock) {
                    try {
                        Timber.d("Loaded more events for last channel, waiting for response")
                        responseLock.wait(5000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

                }
            }
        }

        Timber.d("Done loading more events")
        appRepository.programData.addItems(pendingEventOps)
        Timber.d("Saved ${pendingEventOps.size} events for all channels. Database contains ${appRepository.programData.itemCount} events")
        pendingEventOps.clear()
    }

    companion object {

        fun enqueueWork(context: Context, work: Intent) {
            JobIntentService.enqueueWork(context, HtspIntentService::class.java, 1, work)
        }
    }
}
package com.cyclone.mobile.mapping.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.cyclone.mobile.R
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.graphv2.AtlasRuntime
import com.cyclone.mobile.mapping.crawl.ExistingGateMappingSafetyPort
import com.cyclone.mobile.mapping.crawl.GatewayMappingObservationPort
import com.cyclone.mobile.mapping.crawl.PhoneToolMappingMutationPort
import com.cyclone.mobile.mapping.crawl.PhoneToolMappingNavigationPort
import com.cyclone.mobile.mapping.crawl.Run1MappingSecretsPort
import com.cyclone.mobile.mapping.crawl.SafeMapperWalker
import com.cyclone.mobile.mapping.session.MappingBudget
import com.cyclone.mobile.mapping.session.MappingJob
import com.cyclone.mobile.mapping.session.MappingPlaneRequest
import com.cyclone.mobile.mapping.session.MappingSessionException
import com.cyclone.mobile.mapping.session.MappingSessionRuntime
import com.cyclone.mobile.mapping.session.MappingSessionState
import com.cyclone.mobile.mapping.session.MappingStartRequest
import com.cyclone.mobile.runtime.session.ExecutionSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What the phone UI and the notification show for the current or last mapping job. */
data class MappingRunUi(
    val jobId: String,
    val placeId: String,
    val label: String,
    val state: MappingSessionState,
    val rooms: Int,
    val doors: Int,
    val darkDoors: Int,
    val failureCode: String?,
    val running: Boolean,
)

/**
 * Process owner for autonomous mapping drivers. One driver thread; the controller is still the only
 * mapping state machine and PhoneToolExecutor the only hands.
 */
object MappingDriverRuntime {
    /** Quick first pass: small enough to watch, large enough to show a house. */
    val QUICK_BUDGET = MappingBudget(
        maxNewScreens = 12,
        maxElapsedMs = 3 * 60_000L,
        maxConsecutiveNonProgress = 6,
        maxAttemptsPerDoor = 2,
    )

    private class JobContext(
        val job: MappingJob,
        val label: String,
        val atlas: AtlasStoreMappingPort,
        val session: ControllerSessionPort,
        val driver: MappingDriver,
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cyclone-mapping-driver").apply { isDaemon = true }
    }
    private val jobs = ConcurrentHashMap<String, JobContext>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastJobId: String? = null

    /** Phone Settings → App Maps → Map an app. Foreground plane only in this cut. */
    fun startForPackage(context: Context, packageName: String, budget: MappingBudget = QUICK_BUDGET): MappingJob {
        val controller = MappingSessionRuntime.controller(context)
        val job = controller.start(
            MappingStartRequest(
                placeId = "package:$packageName",
                persona = "mapping",
                plane = MappingPlaneRequest(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, 0),
                budget = budget,
            ),
        )
        launch(context, job, freshStart = true)
        return job
    }

    /**
     * Starts (or continues after resume) the driver for a running job. Only the `mapping` persona and
     * package places can be walked autonomously; anything else fails honestly instead of idling.
     */
    fun launch(context: Context, job: MappingJob, freshStart: Boolean) {
        val appContext = context.applicationContext
        val controller = MappingSessionRuntime.controller(appContext)
        if (job.persona != "mapping") {
            controller.fail(job.mappingJobId, "LIVE_MAPPING_UNSUPPORTED")
            return
        }
        if (!job.placeId.startsWith("package:")) {
            controller.fail(job.mappingJobId, "PLACE_NOT_LAUNCHABLE")
            return
        }
        val ctx = jobs[job.mappingJobId] ?: create(appContext, job).also { jobs[job.mappingJobId] = it }
        lastJobId = job.mappingJobId
        if (!running.add(job.mappingJobId)) return
        MappingNotificationService.show(appContext)
        executor.execute {
            try {
                ctx.driver.run(freshStart)
            } catch (error: Throwable) {
                val current = controller.status(job.mappingJobId)
                if (current != null && !current.state.terminal) {
                    controller.fail(job.mappingJobId, "DRIVER_ERROR")
                }
            } finally {
                running.remove(job.mappingJobId)
            }
        }
    }

    fun pause(context: Context, jobId: String) {
        val controller = MappingSessionRuntime.controller(context)
        val job = controller.status(jobId) ?: return
        if (job.state == MappingSessionState.RUNNING) controller.pause(jobId)
    }

    fun resume(context: Context, jobId: String) {
        val controller = MappingSessionRuntime.controller(context)
        val job = controller.status(jobId) ?: return
        val resumed = try {
            controller.resume(jobId, job.planeRequest)
        } catch (error: MappingSessionException) {
            return
        }
        launch(context, resumed, freshStart = false)
    }

    fun stop(context: Context, jobId: String) {
        MappingSessionRuntime.controller(context).stop(jobId)
    }

    fun current(context: Context): MappingRunUi? {
        val id = lastJobId ?: return null
        val ctx = jobs[id] ?: return null
        val job = MappingSessionRuntime.controller(context).status(id) ?: return null
        return MappingRunUi(
            jobId = id,
            placeId = job.placeId,
            label = ctx.label,
            state = job.state,
            rooms = ctx.atlas.roomCount(),
            doors = ctx.atlas.doorCount(),
            darkDoors = ctx.atlas.darkDoorCount() + ctx.session.blockedDoors,
            failureCode = job.failureCode,
            running = id in running,
        )
    }

    /** Structural-only report for the current/last job (see [MappingReport]). */
    fun report(context: Context): String? {
        val id = lastJobId ?: return null
        val ctx = jobs[id] ?: return null
        val job = MappingSessionRuntime.controller(context).status(id) ?: return null
        return MappingReport.build(job, ctx.atlas, ctx.session, ctx.driver.events).toString(2)
    }

    private fun create(appContext: Context, job: MappingJob): JobContext {
        AppLearnerRuntime.initialize(appContext)
        val controller = MappingSessionRuntime.controller(appContext)
        val packageName = job.placeId.removePrefix("package:")
        val label = runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val atlas = AtlasStoreMappingPort(AtlasRuntime.store, job.placeId, label)
        val session = ControllerSessionPort(controller, job.mappingJobId)
        val secrets = Run1MappingSecretsPort(appContext) { resolution ->
            if (resolution.taskMayResume) resume(appContext, job.mappingJobId)
        }
        val walker = SafeMapperWalker(
            session = session,
            observations = GatewayMappingObservationPort(appContext),
            atlas = atlas,
            safety = ExistingGateMappingSafetyPort(),
            mutations = PhoneToolMappingMutationPort(appContext),
            secrets = secrets,
        )
        val driver = MappingDriver(
            controller = controller,
            jobId = job.mappingJobId,
            walker = walker,
            session = session,
            navigation = PhoneToolMappingNavigationPort(appContext),
            atlas = atlas,
            publishChanges = { changes -> controller.appendAtlasChanges(job.mappingJobId, changes) },
        )
        return JobContext(job, label, atlas, session, driver)
    }
}

/**
 * Visible, user-stoppable mapping. The notification is the "peek chip": place, rooms, state, Stop.
 */
class MappingNotificationService : Service() {
    @Volatile private var stopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MappingDriverRuntime.current(this)?.let { MappingDriverRuntime.stop(this, it.jobId) }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cyclone mapping", NotificationManager.IMPORTANCE_LOW),
        )
        val first = notification(MappingDriverRuntime.current(this))
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, first)
        }
        if (!updating) {
            updating = true
            Thread {
                try {
                    while (!stopped) {
                        val run = MappingDriverRuntime.current(this)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(run))
                        if (run == null || (!run.running && run.state.terminal)) break
                        Thread.sleep(1_000)
                    }
                } catch (_: InterruptedException) {
                } finally {
                    updating = false
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }.apply { isDaemon = true }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(run: MappingRunUi?): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, MappingNotificationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = run?.let { "Mapping ${it.label}" } ?: "Cyclone mapping"
        val text = run?.let { stateLine(it) } ?: "Preparing"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(run?.state?.terminal != true)
            .setOnlyAlertOnce(true)
            .apply { if (run != null && !run.state.terminal) addAction(Notification.Action.Builder(null, "Stop", stop).build()) }
            .build()
    }

    companion object {
        private const val CHANNEL = "cyclone-mapping"
        private const val NOTIFICATION = 905
        private const val ACTION_STOP = "com.cyclone.mobile.mapping.STOP"
        @Volatile private var updating = false

        fun show(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, MappingNotificationService::class.java))
            }
        }

        fun stateLine(run: MappingRunUi): String {
            val counts = "${run.rooms} rooms · ${run.doors} doors"
            return when (run.state) {
                MappingSessionState.RUNNING -> "Walking · $counts · hands off the phone"
                MappingSessionState.PAUSED -> "Paused · $counts"
                MappingSessionState.NEEDS_SECRET -> "Needs a password on the phone · $counts"
                MappingSessionState.HUMAN_CONTROL -> "You have control · $counts"
                MappingSessionState.COMPLETED -> "Done · $counts"
                MappingSessionState.STOPPED -> "Stopped · $counts"
                MappingSessionState.FAILED -> "Stopped: ${run.failureCode ?: "error"} · $counts"
            }
        }
    }
}

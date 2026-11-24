package br.com.exames.inferencelocal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure, testable notification copy & progress formatter.
 * Strictly neutral, non-diagnostic, and free of any PII, filenames, or clinical values.
 */
object BackgroundNotificationCopy {
    fun examProgressTitle(useCloud: Boolean): String =
        if (useCloud) "Lendo exame (nuvem privada)" else "Lendo seu exame"

    fun examProgressText(page: Int, totalPages: Int, block: Int, totalBlocks: Int): String = when {
        totalPages > 0 && totalBlocks > 0 -> "Página $page de $totalPages · Bloco $block de $totalBlocks"
        totalPages > 0 -> "Página $page de $totalPages"
        else -> "Preparando leitura do documento..."
    }

    fun examProgressPercent(
        page: Int,
        totalPages: Int,
        block: Int,
        totalBlocks: Int,
        completedPages: Int,
    ): Int? {
        if (totalPages <= 0) return null
        val blockContribution = if (totalBlocks > 0) {
            (block.coerceAtLeast(0).toFloat() / totalBlocks.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val raw = (completedPages.coerceAtLeast(0) + blockContribution) / totalPages.toFloat()
        return (raw.coerceIn(0.02f, 0.99f) * 100f).roundToInt()
    }

    fun examCompletedTitle(): String = "Leitura do exame concluída"

    fun examCompletedText(): String = "Toque para conferir os resultados no app."

    fun examFailedTitle(): String = "Não foi possível concluir a leitura"

    fun examFailedText(): String = "Toque para abrir o app e verificar o documento."

    fun modelProgressTitle(): String = "Baixando IA local"

    fun modelProgressPercent(downloadedBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        val ratio = (downloadedBytes.coerceIn(0L, totalBytes).toDouble() / totalBytes.toDouble())
        return (ratio * 100.0).roundToInt().coerceIn(0, 100)
    }

    fun modelProgressText(downloadedBytes: Long, totalBytes: Long, verifying: Boolean = false): String {
        if (verifying || (totalBytes > 0L && downloadedBytes >= totalBytes)) {
            return "Verificando integridade (SHA-256)..."
        }
        val pct = modelProgressPercent(downloadedBytes, totalBytes) ?: return "Conectando para baixar pesos locais..."
        return "$pct% · ${formatSize(downloadedBytes)} de ${formatSize(totalBytes)}"
    }

    fun modelCompletedTitle(): String = "Modelo de IA local pronto"

    fun modelCompletedText(): String = "Pesos verificados por SHA-256. O app já pode ler exames offline."

    fun modelFailedTitle(): String = "Download do modelo não concluído"

    fun modelFailedText(errorCode: String?): String = when (errorCode) {
        ModelPreparationFailure.INSUFFICIENT_SPACE.name ->
            "Espaço livre insuficiente no aparelho (~2,65 GB necessários)."
        ModelPreparationFailure.CORRUPT_ASSET.name ->
            "A verificação de integridade SHA-256 falhou. Tente baixar novamente."
        else ->
            "Conexão interrompida durante o download. Toque para tentar novamente."
    }

    internal fun formatSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return if (safe >= gb) {
            String.format(Locale.forLanguageTag("pt-BR"), "%.2f GB", safe / gb)
        } else {
            String.format(Locale.forLanguageTag("pt-BR"), "%.0f MB", safe / mb)
        }
    }
}

/** Shared Android notification channels, builders, and privacy-safe deep-link intents. */
object BackgroundNotifications {
    const val CHANNEL_PROGRESS = "exames_background_progress"
    const val CHANNEL_ALERTS = "exames_background_alerts"

    const val NOTIFICATION_ID_EXAM_PROGRESS = 1001
    const val NOTIFICATION_ID_EXAM_RESULT = 1002
    const val NOTIFICATION_ID_MODEL_PROGRESS = 2001
    const val NOTIFICATION_ID_MODEL_RESULT = 2002

    const val EXTRA_OPEN_EXAM_ID = "br.com.exames.extra.OPEN_EXAM_ID"
    const val EXTRA_OPEN_DESTINATION = "br.com.exames.extra.OPEN_DESTINATION"
    const val DESTINATION_PROCESSING = "processing"
    const val DESTINATION_HELP = "help"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS,
            "Andamento em segundo plano",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Barra de progresso da leitura de exames e do download do modelo de IA local."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Conclusão de leitura e download",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Avisos quando a leitura de um exame ou o download do modelo local terminar."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun createAppLaunchIntent(
        context: Context,
        requestCode: Int,
        examId: String? = null,
        destination: String? = null,
    ): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!examId.isNullOrBlank()) {
            launch.putExtra(EXTRA_OPEN_EXAM_ID, examId)
        }
        if (!destination.isNullOrBlank()) {
            launch.putExtra(EXTRA_OPEN_DESTINATION, destination)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, launch, flags)
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        text: String,
        percent: Int?,
        smallIconRes: Int,
        contentIntent: PendingIntent?,
        cancelIntent: PendingIntent?,
    ): Notification {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent?.coerceIn(0, 100) ?: 0, percent == null)
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        if (cancelIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar",
                cancelIntent,
            )
        }
        return builder.build()
    }

    fun buildDataSyncForegroundInfo(notificationId: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }

    @SuppressLint("MissingPermission")
    fun postCompletionAlert(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        smallIconRes: Int,
        contentIntent: PendingIntent?,
    ) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    fun cancel(context: Context, notificationId: Int) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }
}

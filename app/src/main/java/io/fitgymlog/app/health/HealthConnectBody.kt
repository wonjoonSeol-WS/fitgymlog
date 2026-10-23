package io.fitgymlog.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.fitgymlog.app.coach.BODYWEIGHT_KG
import io.fitgymlog.app.coach.BODY_FAT_PERCENT
import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.coach.MeasurementSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectBody(private val context: Context) {
    private val weight = HealthPermission.getReadPermission(WeightRecord::class)
    private val bodyFat = HealthPermission.getReadPermission(BodyFatRecord::class)
    /** Health Connect lets an app read only the 30 days before access was granted unless it also holds the history permission. */
    fun permissions(): Set<String> = buildSet {
        add(weight)
        add(bodyFat)
        if (isAvailable && client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun canRead(): Boolean = isAvailable && client.permissionController.getGrantedPermissions().let { weight in it || bodyFat in it }

    suspend fun since(start: Instant): List<BodyMeasurement> {
        if (!isAvailable) return emptyList()
        val granted = client.permissionController.getGrantedPermissions()
        val earliest = if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted) start else maxOf(start, Instant.now().minus(Duration.ofDays(30)))
        val range = TimeRangeFilter.after(earliest)
        val weights = if (weight in granted) readAll(WeightRecord::class, range).map { it.time to it.weight.inKilograms } else emptyList()
        val fats = if (bodyFat in granted) readAll(BodyFatRecord::class, range).map { it.time to it.percentage.value } else emptyList()
        return dailyReadings(weights, fats, ZoneId.systemDefault())
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageToken = page))
            records += response.records
            page = response.pageToken
        } while (page != null)
        return records
    }
}

/** A scale can log several readings a day; the day's last one stands for it. */
internal fun dailyReadings(weights: List<Pair<Instant, Double>>, fats: List<Pair<Instant, Double>>, zone: ZoneId): List<BodyMeasurement> {
    fun lastPerDay(readings: List<Pair<Instant, Double>>) =
        readings.groupBy { it.first.atZone(zone).toLocalDate().toString() }.mapValues { (_, day) -> day.maxBy { it.first }.second }
    val weightByDay = lastPerDay(weights.filter { it.second in BODYWEIGHT_KG })
    val fatByDay = lastPerDay(fats.filter { it.second in BODY_FAT_PERCENT })
    return (weightByDay.keys + fatByDay.keys).sorted().map { date ->
        BodyMeasurement(date, weightKg = weightByDay[date], bodyFatPercent = fatByDay[date], source = MeasurementSource.HEALTH_CONNECT)
    }
}

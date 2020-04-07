package info.nightscout.androidaps.plugins.source

import android.content.Intent
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.GlucoseValue
import info.nightscout.androidaps.database.transactions.CgmSourceTransaction
import info.nightscout.androidaps.interfaces.BgSourceInterface
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.general.nsclient.data.NSSgv
import info.nightscout.androidaps.utils.determineSourceSensor
import info.nightscout.androidaps.utils.extensions.plusAssign
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import info.nightscout.androidaps.utils.toTrendArrow
import io.reactivex.disposables.CompositeDisposable
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NSClientSourcePlugin @Inject constructor(
    injector: HasAndroidInjector,
    resourceHelper: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val sp: SP,
    private val repository: AppRepository
) : PluginBase(PluginDescription()
    .mainType(PluginType.BGSOURCE)
    .fragmentClass(BGSourceFragment::class.java.name)
    .pluginName(R.string.nsclientbg)
    .description(R.string.description_source_ns_client),
    aapsLogger, resourceHelper, injector
), BgSourceInterface {

    private val disposable = CompositeDisposable()

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    private var lastBGTimeStamp: Long = 0
    private var isAdvancedFilteringEnabled = false

    override fun advancedFilteringSupported(): Boolean {
        return isAdvancedFilteringEnabled
    }

    override fun handleNewData(intent: Intent) {
        if (!isEnabled(PluginType.BGSOURCE) && !sp.getBoolean(R.string.key_ns_autobackfill, true)) return
        val bundles = intent.extras ?: return
        val glucoseValues = mutableListOf<CgmSourceTransaction.TransactionGlucoseValue?>()
        if (bundles.containsKey("sgv")) {
            glucoseValues += JSONObject(bundles.getString("sgv")).toGlucoseValue()
        }
        if (bundles.containsKey("sgvs")) {
            val sgvString = bundles.getString("sgvs")
            aapsLogger.debug(LTag.BGSOURCE, "Received NS Data: $sgvString")
            val jsonArray = JSONArray(sgvString)
            for (i in 0 until jsonArray.length()) {
                glucoseValues += jsonArray.getJSONObject(i).toGlucoseValue()
            }
        }
        disposable += repository.runTransaction(CgmSourceTransaction(glucoseValues.filterNotNull(), emptyList(), null)).subscribe({}, {
            aapsLogger.error(LTag.BGSOURCE, "Error while saving values from Nightscout App", it)
        })
        // Objectives 0
        sp.putBoolean(R.string.key_ObjectivesbgIsAvailableInNS, true)
    }

    private fun JSONObject.toGlucoseValue() = NSSgv(this).run {
        val source = getString("device")
        val timestamp = mills
        val value = mgdl?.toDouble()
        val raw = unfiltered?.toDouble()
        val noise = noise?.toDouble()
        val trendArrow = direction?.toTrendArrow() ?: GlucoseValue.TrendArrow.NONE
        val nightScout = id
        val sourceSensor = source?.determineSourceSensor() ?: GlucoseValue.SourceSensor.UNKNOWN
        detectSource(source, mills)
        if (timestamp != null && value != null) {
            CgmSourceTransaction.TransactionGlucoseValue(
                timestamp = timestamp,
                value = value,
                raw = raw,
                noise = noise,
                trendArrow = trendArrow,
                nightscoutId = nightScout,
                sourceSensor = sourceSensor
            )
        } else null
    }

    private fun detectSource(source: String, timeStamp: Long) {
        if (timeStamp > lastBGTimeStamp) {
            isAdvancedFilteringEnabled = source.contains("G5 Native") || source.contains("G6 Native") || source.contains("AndroidAPS-Dexcom")
            lastBGTimeStamp = timeStamp
        }
    }
}
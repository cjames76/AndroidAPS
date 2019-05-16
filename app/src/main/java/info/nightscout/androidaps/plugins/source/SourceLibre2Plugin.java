package info.nightscout.androidaps.plugins.source;

import android.content.Intent;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.interfaces.BgSourceInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.services.Intents;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

public class SourceLibre2Plugin extends PluginBase implements BgSourceInterface {

    private static long SMOOTHING_DURATION = TimeUnit.MINUTES.toMillis(20);
    private static long TREND_DURATION = TimeUnit.MINUTES.toMillis(10);

    private static Logger log = LoggerFactory.getLogger(L.BGSOURCE);

    private static SourceLibre2Plugin plugin = null;

    public static SourceLibre2Plugin getPlugin() {
        if (plugin == null) plugin = new SourceLibre2Plugin();
        return plugin;
    }

    private SourceLibre2Plugin() {
        super(new PluginDescription()
                .mainType(PluginType.BGSOURCE)
                .fragmentClass(BGSourceFragment.class.getName())
                .preferencesId(R.xml.pref_bgsource_libre2)
                .pluginName(R.string.libre2_app)
                .shortName(R.string.libre2_short)
                .description(R.string.libre2_description));
    }

    @Override
    public boolean advancedFilteringSupported() {
        return true;
    }

    @Override
    public void handleNewData(Intent intent) {
        if (!isEnabled(PluginType.BGSOURCE)) return;
        if (Intents.LIBRE2_ACTIVATION.equals(intent.getAction()))
            saveSensorStartTime(intent.getBundleExtra("sensor"));
        if (Intents.LIBRE2_BG.equals(intent.getAction())) {
            Libre2RawValue currentRawValue = processIntent(intent);
            if (currentRawValue == null) return;
            BgReading lastBG = MainApp.getDbHelper().getBgReadingBefore(currentRawValue.timestamp);
            if (lastBG == null || currentRawValue.timestamp - lastBG.date > TimeUnit.SECONDS.toMillis(270)) {
                List<Libre2RawValue> smoothingValues = getValuesSince(currentRawValue, SMOOTHING_DURATION);
                List<Libre2RawValue> trendValues = getValuesSince(currentRawValue, TREND_DURATION);
                smoothingValues.add(currentRawValue);
                trendValues.add(currentRawValue);
                processValues(currentRawValue, smoothingValues, trendValues);
            }
            MainApp.getDbHelper().createOrUpdate(currentRawValue);
        }
    }

    private List<Libre2RawValue> getValuesSince(Libre2RawValue currentRawValue, long smoothingDuration) {
        return MainApp.getDbHelper().getLibre2RawValuesBetween(currentRawValue.serial,
                currentRawValue.timestamp - smoothingDuration, currentRawValue.timestamp);
    }

    private static void processValues(Libre2RawValue currentValue, List<Libre2RawValue> smoothingValues, List<Libre2RawValue> trendValues) {
        BgReading bgReading = new BgReading();
        bgReading.date = currentValue.timestamp;
        bgReading.raw = currentValue.glucose;
        bgReading.value = calculateWeightedAverage(smoothingValues, currentValue.timestamp);
        bgReading.direction = calculateTrend(trendValues);
        MainApp.getDbHelper().createIfNotExists(bgReading, "Libre2");
        if (SP.getBoolean(R.string.key_dexcomg5_nsupload, false))
            NSUpload.uploadBg(bgReading, "AndroidAPS-Libre2");
        if (SP.getBoolean(R.string.key_dexcomg5_xdripupload, false))
            NSUpload.sendToXdrip(bgReading);
    }

    private static Libre2RawValue processIntent(Intent intent) {
        Bundle sas = intent.getBundleExtra("sas");
        if (sas != null) saveSensorStartTime(sas.getBundle("currentSensor"));
        if (!intent.hasExtra("glucose") || !intent.hasExtra("timestamp") || !intent.hasExtra("bleManager")) {
            log.error("Received faulty intent from LibreLink.");
            return null;
        }
        double glucose = intent.getDoubleExtra("glucose", 0);
        long timestamp = intent.getLongExtra("timestamp", 0);
        String serial = intent.getBundleExtra("bleManager").getString("sensorSerial");
        if (serial == null) {
            log.error("Received faulty intent from LibreLink.");
            return null;
        }
        log.debug("Received BG reading from LibreLink: glucose=" + glucose + " timestamp=" + timestamp + " serial=" + serial);

        Libre2RawValue rawValue = new Libre2RawValue();
        rawValue.timestamp = timestamp;
        rawValue.glucose = glucose;
        rawValue.serial = serial;
        return rawValue;
    }

    private static void saveSensorStartTime(Bundle sensor) {
        if (sensor != null && sensor.containsKey("sensorStartTime")) {
            long sensorStartTime = sensor.getLong("sensorStartTime");
            if (MainApp.getDbHelper().getCareportalEventFromTimestamp(sensorStartTime) == null) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("enteredBy", "AndroidAPS-Libre2");
                    data.put("created_at", DateUtil.toISOString(sensorStartTime));
                    data.put("eventType", CareportalEvent.SENSORCHANGE);
                    NSUpload.uploadCareportalEntryToNS(data);
                } catch (JSONException e) {
                    log.error("Exception in Libre 2 plugin", e);
                }
            }
        }
    }

    private static double calculateWeightedAverage(List<Libre2RawValue> rawValues, long now) {
        double sum = 0;
        double weightSum = 0;
        for (Libre2RawValue rawValue : rawValues) {
            double weight = 1 - ((now-rawValue.timestamp)/ (double) SMOOTHING_DURATION);
            sum += rawValue.glucose * weight;
            weightSum += weight;
        }
        return Math.round(sum / weightSum);
    }

    private static String calculateTrend(List<Libre2RawValue> rawValues) {
        if (rawValues.size() <= 1) return "NONE";
        Collections.sort(rawValues, (o1, o2) -> Long.compare(o1.timestamp, o2.timestamp));

        long oldestTimestamp = rawValues.get(0).timestamp;
        double sumX = 0;
        double sumY = 0;
        for (Libre2RawValue value : rawValues) {
            sumX += (double) (value.timestamp - oldestTimestamp) / (double) TimeUnit.MINUTES.toMillis(1);
            sumY += value.glucose;
        }
        double averageGlucose = sumY / rawValues.size();
        double averageTimestamp = sumX / rawValues.size();
        double a = 0;
        double b = 0;
        for (Libre2RawValue value : rawValues) {
            a += ((double) (value.timestamp - oldestTimestamp) / (double) TimeUnit.MINUTES.toMillis(1) - averageTimestamp) * (value.glucose - averageGlucose);
            b += Math.pow((double) (value.timestamp - oldestTimestamp) / (double) TimeUnit.MINUTES.toMillis(1) - averageTimestamp, 2);
        }
        double slope = a / b;
        return determineTrendArrow(slope);
    }

    private static String determineTrendArrow(double slope) {
        if (slope <= -3.5) return "DoubleDown";
        else if (slope <= -2) return "SingleDown";
        else if (slope <= -1) return "FortyFiveDown";
        else if (slope <= 1) return "Flat";
        else if (slope <= 2) return "FortyFiveUp";
        else if (slope <= 3.5) return "SingleUp";
        else return "DoubleUp";
    }
}

package ssatr.device;

import java.util.concurrent.BlockingQueue;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long nextRelease = System.nanoTime();

        while (!Thread.currentThread().isInterrupted()){
            long periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMs);

            if (periodNanos <= 0) {
                System.out.println("Perioada trebuie sa fie pozitiva!");
                periodMs = 1000;
                periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMs);
            }

            long now = System.nanoTime();

            if (now < nextRelease) {
                try {
                    TimeUnit.NANOSECONDS.sleep(nextRelease - now);
                } catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            double temperature = sensor.read();
            long timestamp = System.currentTimeMillis();

            String message = String.format(
                    Locale.US,
                    "%s;%d;%.3f",
                    deviceId,
                    timestamp,
                    temperature
            );

            mqtt.publish(Config.telemetryTopic(deviceId), message);

            if (temperature > threshold) {
                alarmEvents.offer(temperature);
            }

            // Următoarea eliberare rămâne pe grila periodică.
            nextRelease += periodNanos;

            long nowAfterJob = System.nanoTime();

            // Dacă am întârziat, sărim peste eliberările depășite,
            // păstrând originea grilei.
            if (nowAfterJob > nextRelease) {
                long skippedReleases =
                        (nowAfterJob - nextRelease) / periodNanos + 1;

                nextRelease += skippedReleases * periodNanos;
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}

package ssatr.device;

import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    private final PeriodicTelemetryTask periodic;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;

        this.periodic = periodic;
    }

    @Override
    public void run() {
        long lastAlarmTime = 0;

        while (!Thread.currentThread().isInterrupted()){
            try {
                double temperature = alarmEvents.take();

                long now = System.currentTimeMillis();
                long elapsed = now - lastAlarmTime;

                if (lastAlarmTime != 0 && elapsed <MIN_INTERARRIVAL_MS){
                    System.out.println(
                            "Alarma respinsa: " + deviceId
                            + " : interval prea mic (" + elapsed + " ms)"
                    );
                    continue;
                }

                double threshold = periodic.getThreshold();

                String message = String.format(
                        java.util.Locale.US,
                        "%s;%d;%.2f;%.3f",
                        deviceId,
                        now,
                        temperature,
                        threshold
                );

                mqtt.publish(Config.alarmTopic(deviceId), message);

                lastAlarmTime = now;

                System.out.println("Alarma: " + message);
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}

package ssatr.device;

import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()){
            try {
                String command = commands.take().trim();

                String[] parts = command.split("\\s+");

                if (parts.length == 0 || parts[0].isBlank()){
                    publishStatus("ERROR empty command");
                    continue;
                }

                String commandName = parts[0];

                switch (commandName) {
                    case "SET_INTERVAL":
                        if (parts.length != 2) {
                            publishStatus("ERROR format: SET_INTERVAL <ms>");
                            continue;
                        }

                        long interval = Long.parseLong(parts[1]);

                        if (interval <= 0) {
                            publishStatus("ERROR intervalul trebuie sa fie pozitiv!");
                            continue;
                        }

                        periodic.setPeriodMs(interval);
                        publishStatus("OK SET_INTERVAL " + interval);
                        break;

                    case "SET_THRESHOLD":
                        if (parts.length != 2) {
                            publishStatus("ERROR format: SET_THRESHOLD <valoare>");
                            continue;
                        }

                        double threshold = Double.parseDouble(parts[1]);

                        if (!Double.isFinite(threshold)){
                            publishStatus("ERROR pragul trebuie sa fie un numar finit");
                            continue;
                        }

                        periodic.setThreshold(threshold);
                        publishStatus("OK SET_THRESHOLD " + threshold);
                        break;

                    case "RESET":
                        if (parts.length != 1){
                            publishStatus("ERROR format: RESET");
                            continue;
                        }

                        sensor.reset();
                        publishStatus("OK RESET");
                        break;

                    case "STATUS":
                        if (parts.length != 1){
                            publishStatus("ERROR format: STATUS");
                            continue;
                        }

                        publishStatus(
                                String.format(
                                        java.util.Locale.US,
                                        "OK STATUS interval=%d threshold=%.3f",
                                        periodic.getPeriodMs(),
                                        periodic.getThreshold()
                                )
                        );
                        break;

                    default:
                        publishStatus("ERROR comanda necunoscuta: " + commandName);
                }
            }catch (NumberFormatException e) {
                publishStatus("ERROR parametru numeric invalid!");

            }catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void publishStatus(String text){
        String message = String.format(
                java.util.Locale.US,
                "%s;%d;%s",
                deviceId,
                System.currentTimeMillis(),
                text
        );

        mqtt.publish(Config.statusTopic(deviceId), message);

        System.out.println("Status: " + message);
    }
}

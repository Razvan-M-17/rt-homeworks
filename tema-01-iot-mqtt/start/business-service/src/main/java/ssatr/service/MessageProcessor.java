package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()){
            try{
                String message = inbox.take();

                storage.append(kind, message);
                processMessage(message);
            } catch (Exception e) {
                System.out.println(
                        "Mesaj invalid: " + kind + ": " + e.getMessage()
                );
            }
        }
    }

    private void processMessage(String message){
        String[] parts = message.split(";", 4);

        if (kind.equals("telemetry")){
            if (parts.length != 3){
                throw new IllegalArgumentException(
                        "format telemetry invalid"
                );
            }

            String deviceId = parts[0];
            long timestamp = Long.parseLong(parts[1]);
            double temperature = Double.parseDouble(parts[2]);

            dashboard.updateTelemetry(
                    deviceId,
                    timestamp,
                    temperature
            );
        } else if (kind.equals("alarm")){
            if (parts.length != 4){
                throw new IllegalArgumentException(
                        "format alarm invalid"
                );
            }

            String deviceId = parts[0];
            long timestamp = Long.parseLong(parts[1]);
            double temperature = Double.parseDouble(parts[2]);

            dashboard.updateAlarm(
                    deviceId,
                    timestamp,
                    temperature
            );

            System.out.println("[ALARM] " + message);

        } else if (kind.equals("status")){
            if (parts.length != 3) {
                throw new IllegalArgumentException(
                        "format status invalid"
                );
            }

            String deviceId = parts[0];
            long timestamp = Long.parseLong(parts[1]);

            dashboard.updateStatus(
                    deviceId,
                    timestamp
            );

            System.out.println("[STATUS] " + message);
        } else {
            throw new IllegalArgumentException(
                    "tip necunoscut: " + kind
            );
        }
    }
}
